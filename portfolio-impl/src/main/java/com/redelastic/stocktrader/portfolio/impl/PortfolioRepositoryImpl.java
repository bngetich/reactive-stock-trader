package com.redelastic.stocktrader.portfolio.impl;

import akka.japi.Pair;
import akka.stream.javadsl.Source;
import com.lightbend.lagom.javadsl.persistence.AggregateEventTag;
import com.lightbend.lagom.javadsl.persistence.Offset;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRef;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;
import com.redelastic.stocktrader.broker.api.BrokerService;
import com.redelastic.stocktrader.order.Order;
import com.redelastic.stocktrader.portfolio.api.*;
import org.pcollections.ConsPStack;
import org.pcollections.PSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.stream.Collectors.toList;

public class PortfolioRepositoryImpl implements PortfolioRepository {

    private Logger log = LoggerFactory.getLogger(PortfolioRepositoryImpl.class);

    private final BrokerService brokerService;

    private final PersistentEntityRegistry persistentEntities;

    @Inject
    public PortfolioRepositoryImpl(BrokerService brokerService,
                                   PersistentEntityRegistry persistentEntities) {
        this.brokerService = brokerService;
        this.persistentEntities = persistentEntities;
        persistentEntities.register(PortfolioEntity.class);
    }

    /**
     * Initialize a new portfolio. We first generate a new ID for it and send it a setup message. In the very unlikely
     * circumstance that the ID is already in use we'll get an exception when we send the initialize command, we should
     * retry with a new UUID.
     * @param request
     * @return
     */
    // TODO: Implement retry logic. Theoretically the chance of a collision is astronomically low *given* everything else works.
    @Override
    public CompletionStage<String> open(NewPortfolioRequest request) {
        UUID uuid = UUID.randomUUID();
        String portfolioId = uuid.toString();
        PersistentEntityRef<PortfolioCommand> ref = persistentEntities.refFor(PortfolioEntity.class, portfolioId);
        return ref.ask(new PortfolioCommand.Open(request.getName()))
                .thenApply(done -> portfolioId);
    }

    @Override
    public CompletionStage<PortfolioView> get(String portfolioId) {
        return persistentEntities.refFor(PortfolioEntity.class, portfolioId)
                .ask(PortfolioCommand.GetState.INSTANCE)
                .thenCompose(portfolio ->
                    priceHoldings(portfolio.getHoldings().asSequence())
                    .thenApply(valuedHoldings ->
                        PortfolioView.builder()
                                .portfolioId(portfolioId)
                                .funds(portfolio.getFunds())
                                .loyaltyLevel(portfolio.getLoyaltyLevel())
                                .holdings(valuedHoldings)
                                .build()
                    )
                );
    }

    private CompletionStage<PSequence<ValuedHolding>> priceHoldings(PSequence<Holding> holdings) {
        // TODO deal with request failures
        // TODO timeout
        List<CompletableFuture<ValuedHolding>> requests = holdings.stream().map(valuedHolding ->
                brokerService
                        .getQuote()
                        .invoke(valuedHolding.getSymbol())
                        .thenApply(quote ->
                                new ValuedHolding(
                                        valuedHolding.getSymbol(),
                                        valuedHolding.getShareCount(),
                                        quote.getSharePrice().multiply(BigDecimal.valueOf(valuedHolding.getShareCount()))))
                        .toCompletableFuture()
        ).collect(toList());

        return CompletableFuture.allOf(requests.toArray(new CompletableFuture<?>[0]))
                .thenApply(done ->
                    requests.stream()
                            .map(response -> response.toCompletableFuture().join())
                        .collect(toList())
                ).thenApply(ConsPStack::from);
    }

    public PersistentEntityRef<PortfolioCommand> getRef(String portfolioId) {
        return persistentEntities.refFor(PortfolioEntity.class, portfolioId);
    }

    public Source<Pair<Order, Offset>, ?> ordersStream(AggregateEventTag<PortfolioEvent> tag, Offset offset) {
        return persistentEntities.eventStream(tag, offset).filter(eventOffset ->
                eventOffset.first() instanceof PortfolioEvent.OrderPlaced
        ).mapAsync(1, eventOffset -> {
                PortfolioEvent.OrderPlaced order = (PortfolioEvent.OrderPlaced)eventOffset.first();
                log.warn(String.format("Publishing order %s", order.getOrder().getOrderId()));
                return CompletableFuture.completedFuture(Pair.create(
                        order.getOrder(),
                        eventOffset.second()
                ));

        });
    }

}