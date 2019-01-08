package com.redelastic.stocktrader.broker.api;

import akka.Done;
import akka.NotUsed;
import com.lightbend.lagom.javadsl.api.Descriptor;
import com.lightbend.lagom.javadsl.api.Service;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.api.broker.Topic;
import com.lightbend.lagom.javadsl.api.transport.Method;
import com.redelastic.stocktrader.order.Order;

import static com.lightbend.lagom.javadsl.api.Service.restCall;
import static com.lightbend.lagom.javadsl.api.Service.named;
import static com.lightbend.lagom.javadsl.api.Service.topic;

public interface BrokerService extends Service {

  ServiceCall<String, Quote> getQuote();

  ServiceCall<NotUsed, OrderStatus> getOrderStatus(String orderId);

  ServiceCall<Order, Done> placeOrder();

  String ORDER_RESULTS_TOPIC_ID = "OrderResults";
  Topic<OrderResult> orderResults();

  @Override
  default Descriptor descriptor() {
    // @formatter:off
    return named("broker").withCalls(
        restCall(Method.GET, "/api/order/:orderId", this::getQuote)
    ).withTopics(
            topic(ORDER_RESULTS_TOPIC_ID, this::orderResults)
    );
    // @formatter:on
  }
}