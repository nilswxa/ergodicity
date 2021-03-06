package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.ReplicationStreamListenerStubActor.DispatchData
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OptOrder, FutOrder}
import com.ergodicity.core._
import org.joda.time.DateTime
import scala.collection.mutable
import com.ergodicity.marketdb.model.OrderPayload

object OrdersService {

  case class OrderEvent(orderId: Long, time: DateTime, status: Int, action: Short, dir: Short, price: BigDecimal, amount: Int, amount_rest: Int, deal: Option[(Long, BigDecimal)])

  case class FutureOrder(session: SessionId, id: IsinId, event: OrderEvent)

  case class OptionOrder(session: SessionId, id: IsinId, event: OrderEvent)

  object Action {
    val Cancel: Short = 0
    val Create: Short = 1
    val Fill: Short = 2
  }

  class ManagedOrder(orderId: Long, dir: OrderDirection, isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType, time: DateTime)
                    (implicit context: SessionContext, service: OrdersService) {
    private[this] def check(assertion: Boolean, message: String = "Check error") {
      if (!assertion) throw new IllegalStateException(message)
    }

    var rest = amount

    dispatch(OrderEvent(orderId, time, orderType.toInt, Action.Create, dir.toShort, price, amount, amount, None))

    def fill(time: DateTime, amount: Int, deal: (Long, BigDecimal)) {
      check(rest - amount >= 0, "Rest amount after fill could not be less then zero")
      rest -= amount
      dispatch(OrderEvent(orderId, time, orderType.toInt, Action.Fill, dir.toShort, price, amount, rest, Some(deal)))

    }

    def cancel(time: DateTime) {
      check(rest > 0, "Rest amount should be greater then zero")
      dispatch(OrderEvent(orderId, time, orderType.toInt, Action.Cancel, dir.toShort, price, rest, 0, None))
      rest = 0
    }

    private[this] def dispatch(event: OrderEvent) {
      if (context.isFuture(isin)) {
        service.dispatch(FutureOrder(context.sessionId, context.isinId(isin).get, event))
      } else if (context.isOption(isin)) {
        service.dispatch(OptionOrder(context.sessionId, context.isinId(isin).get, event))
      } else throw new IllegalStateException("Can't assign security to Futures either Options")
    }
  }

  val Revision = 1

  implicit def futureOrder2plaza(future: FutureOrder) = new {
    val (sessionId, isinId, event) = (future.session.opt, future.id, future.event)

    def asPlazaRecord: OptOrder.orders_log = {
      val buff = allocate(Size.OptOrder)
      val cgate = new OptOrder.orders_log(buff)

      cgate.set_replRev(Revision)
      cgate.set_sess_id(sessionId)
      cgate.set_isin_id(isinId.id)

      import event._
      cgate.set_id_ord(orderId)
      cgate.set_moment(time.getMillis)
      cgate.set_status(status)
      cgate.set_action(action.toByte)
      cgate.set_dir(dir.toByte)
      cgate.set_price(price)
      cgate.set_amount(amount)
      cgate.set_amount_rest(amount_rest)
      deal.foreach {
        case (dealId, dealPrice) =>
          cgate.set_id_deal(dealId)
          cgate.set_deal_price(dealPrice)
      }
      cgate
    }
  }


  implicit def optionOrder2plaza(option: OptionOrder) = new {
    val (sessionId, isinId, event) = (option.session.opt, option.id, option.event)

    def asPlazaRecord: OptOrder.orders_log = {
      val buff = allocate(Size.OptOrder)
      val cgate = new OptOrder.orders_log(buff)

      cgate.set_replRev(Revision)
      cgate.set_sess_id(sessionId)
      cgate.set_isin_id(isinId.id)

      import event._
      cgate.set_id_ord(orderId)
      cgate.set_moment(time.getMillis)
      cgate.set_status(status)
      cgate.set_action(action.toByte)
      cgate.set_dir(dir.toByte)
      cgate.set_price(price)
      cgate.set_amount(amount)
      cgate.set_amount_rest(amount_rest)
      deal.foreach {
        case (dealId, dealPrice) =>
          cgate.set_id_deal(dealId)
          cgate.set_deal_price(dealPrice)
      }
      cgate
    }
  }
}

class OrdersService(futOrders: ActorRef, optOrders: ActorRef)(implicit context: SessionContext) {

  import OrdersService._

  val orders = mutable.Map[Long, ManagedOrder]()

  private[this] implicit val self = this

  def dispatch(payloads: OrderPayload*) {
    payloads.foreach {
      case order if (order.action == Action.Create && !orders.contains(order.orderId)) =>
        orders(order.orderId) = create(order.orderId, OrderDirection(order.dir), Isin(order.security.isin), order.amount, order.price, OrderType(order.status), order.time)

      case order if (order.action == Action.Cancel && orders.contains(order.orderId)) =>
        orders(order.orderId).cancel(order.time)

      case order if (order.action == Action.Fill && orders.contains(order.orderId) && order.deal.isDefined) =>
        orders(order.orderId).fill(order.time, order.amount, order.deal.get)

      case order if (order.action == Action.Fill && orders.contains(order.orderId) && !order.deal.isDefined) =>
        throw new IllegalStateException("Deal is not defined for order = " + order)

      case order if (order.action == Action.Create && orders.contains(order.orderId)) =>
        throw new IllegalStateException("Order for given id already created; Order = " + order)

      case order if (order.action != Action.Create && !orders.contains(order.orderId)) =>
        throw new IllegalStateException("No order with given id; Order = " + order)
    }
  }

  def create(orderId: Long, dir: OrderDirection, isin: Isin, amount: Int, price: BigDecimal, orderType: OrderType, time: DateTime) =
    new ManagedOrder(orderId, dir, isin, amount, price, orderType, time)

  private[OrdersService] def dispatch(future: FutureOrder) {
    futOrders ! DispatchData(StreamData(FutOrder.orders_log.TABLE_INDEX, future.asPlazaRecord.getData) :: Nil)
  }

  private[OrdersService] def dispatch(option: OptionOrder) {
    optOrders ! DispatchData(StreamData(OptOrder.orders_log.TABLE_INDEX, option.asPlazaRecord.getData) :: Nil)
  }
}