package com.miriamlaurel.fxcore.market

import java.time.Instant

import com.miriamlaurel.fxcore.instrument.{CurrencyPair, Instrument}
import com.miriamlaurel.fxcore.{Me, Timestamp}

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

class OrderBook private(val instrument: Instrument,
                        override val timestamp: Instant,
                        val bids: SortedMap[BigDecimal, Map[OrderKey, Order]] = SortedMap()(Ordering.BigDecimal.reverse),
                        val asks: SortedMap[BigDecimal, Map[OrderKey, Order]] = SortedMap()(Ordering.BigDecimal),
                        byKey: Map[OrderKey, Order] = Map.empty) extends Timestamp {

  lazy val bestBid: Option[BigDecimal] = for (h <- bids.headOption) yield h._1
  lazy val bestAsk: Option[BigDecimal] = for (h <- asks.headOption) yield h._1
  lazy val best = Quote(instrument, bestBid, bestAsk, timestamp)

  def isFull: Boolean = bids.nonEmpty && asks.nonEmpty

  def +(order: Order, timestamp: Instant): OrderBook = {
    require(instrument == order.key.instrument, "Order instrument should match order book instrument")
    val line = if (order.key.side == QuoteSide.Bid) bids else asks
    val newTimestamp = if (this.timestamp.compareTo(timestamp) > 0) this.timestamp else timestamp
    byKey.get(order.key) match {
      // order with same key exists, need to be replaced
      case Some(existingOrder) =>
        val oldPrice = existingOrder.price
        val newPrice = order.price
        val removedOldPrice = line(oldPrice) - existingOrder.key
        val addedNewPrice = if (line.contains(newPrice)) line(newPrice) + (order.key -> order) else Map(order.key -> order)
        val tmpLine = if (removedOldPrice.isEmpty) line - oldPrice else line + (oldPrice -> removedOldPrice)
        val newLine = tmpLine + (newPrice -> addedNewPrice)
        val newByKey = byKey + (order.key -> order)
        if (order.key.side == QuoteSide.Bid) new OrderBook(instrument, newTimestamp, newLine, asks, newByKey)
        else new OrderBook(instrument, timestamp, bids, newLine, newByKey)
      // no order with same key; adding new order to the book
      case None =>
        val newLine = line.get(order.price) match {
          case Some(orders) => line + (order.price -> (orders + (order.key -> order)))
          case None => line + (order.price -> Map(order.key -> order))
        }
        val newByKey = byKey + (order.key -> order)
        if (order.key.side == QuoteSide.Bid) new OrderBook(instrument, newTimestamp, newLine, asks, newByKey)
        else new OrderBook(instrument, timestamp, bids, newLine, newByKey)
    }
  }

  def -(key: OrderKey): OrderBook = byKey.get(key) match {
    case Some(order) =>
      val line = if (order.key.side == QuoteSide.Bid) bids else asks
      val removedOld = line(order.price) - key
      val newLine = if (removedOld.isEmpty) line - order.price else line + (order.price -> removedOld)
      val newByKey = byKey - key
      if (order.key.side == QuoteSide.Bid) new OrderBook(instrument, timestamp, newLine, asks, newByKey)
      else new OrderBook(instrument, timestamp, bids, newLine, newByKey)
    case None => throw new NoSuchElementException(s"Order not found in the order book: $key")
  }

  @tailrec
  private def slice(side: QuoteSide.Value, amount: BigDecimal, taken: BigDecimal, acc: List[Order]): List[Order] = {
    val line = if (side == QuoteSide.Bid) bids else asks
    val first = line.head._2.head._2
    if ((taken + first.amount) >= amount) first :: acc else (this - first.key).slice(side, amount, taken + first.amount, first :: acc)
  }

  def slice(side: QuoteSide.Value, amount: BigDecimal): List[Order] = slice(side, amount, BigDecimal(0), List.empty)

  def trim(amount: BigDecimal): OrderBook = OrderBook(timestamp, slice(QuoteSide.Bid, amount) ::: slice(QuoteSide.Ask, amount))

  def quote(amount: BigDecimal): Quote = {
    require(amount >= 0)
    if (amount == BigDecimal(0)) best
    else {
      val sliceBid = slice(QuoteSide.Bid, amount)
      val sliceAsk = slice(QuoteSide.Ask, amount)
      val bid = if (sliceBid.nonEmpty) Some(weightedAvg(sliceBid)) else None
      val ask = if (sliceAsk.nonEmpty) Some(weightedAvg(sliceAsk)) else None
      Quote(instrument, bid, ask, timestamp)
    }
  }

  override def toString = OrderBook.toCsv(this)

  private def weightedAvg(orders: List[Order]): BigDecimal =
    orders.map(order => order.price * order.amount).sum / orders.map(_.amount).sum
}

object OrderBook {

  def empty(instrument: Instrument): OrderBook = new OrderBook(instrument, Instant.EPOCH)

  def apply(timestamp: Instant, orders: Iterable[Order]): OrderBook = orders.headOption match {
    case Some(order) =>
      val start: OrderBook = empty(order.key.instrument)
      orders.foldLeft(start)((a: OrderBook, b: Order) => a.+(b, timestamp))
    case None => throw new IllegalArgumentException("Can't make an order book from an empty list")
  }

  def apply(orders: Iterable[Order]): OrderBook = apply(Instant.now(), orders)
  
  def apply(orders: (Order)*): OrderBook = apply(orders: _*)

  def apply(s: String) = fromCsv(s)

  def fromCsv(csv: String): OrderBook = {
    def pair[A](l: List[A]): List[(A, A)] = l.grouped(2).collect { case List(a, b) => (a, b) }.toList
    val tokens = csv.split(",")
    val ts = Instant.ofEpochMilli(tokens(0).toLong)
    val instrument = CurrencyPair(tokens(1))
    val asksIndex = tokens.indexOf("ASKS")
    val bidS: List[(String, String)] = pair(tokens.slice(3, asksIndex).toList)
    val askS: List[(String, String)] = pair(tokens.slice(asksIndex + 1, tokens.length).toList)
    val orders = bidS.zipWithIndex.map(
      n => Order(Me, instrument, QuoteSide.Bid, n._2.toString, BigDecimal(n._1._2), BigDecimal(n._1._1))) ++
      askS.zipWithIndex.map(n => Order(Me, instrument, QuoteSide.Ask, n._2.toString, BigDecimal(n._1._2), BigDecimal(n._1._1)))
    OrderBook(ts, orders)
  }


  def toCsv(orderBook: OrderBook) = {
    orderBook.timestamp.toEpochMilli + "," + orderBook.instrument.toString + ",BIDS," +
      orderBook.bids.values.flatten.map(o => o._2.price.toString + "," + o._2.amount.toString).mkString(",") +
      ",ASKS," +
      orderBook.asks.values.flatten.map(o => o._2.price.toString + "," + o._2.amount.toString).mkString(",")
  }
}