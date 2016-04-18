package com.miriamlaurel.fxcore.accounting

import java.time.Instant
import com.miriamlaurel.fxcore.Money
import com.miriamlaurel.fxcore.portfolio.{PositionSide, Position}
import com.miriamlaurel.fxcore.market.Market
import com.miriamlaurel.fxcore.asset.AssetClass

case class Deal(position: Position, closePrice: BigDecimal, override val timestamp: Instant, override val amount: Money) extends Entry {
  override def convert(to: AssetClass, market: Market): Option[Entry] = for {
      converted <- market.convert(amount, to, PositionSide.close(position.side), position.absoluteAmount)
  } yield copy(amount = converted)
}