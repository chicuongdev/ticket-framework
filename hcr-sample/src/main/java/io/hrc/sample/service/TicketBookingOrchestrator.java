package io.hrc.sample.service;

import io.hrc.core.domain.OrderRequest;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.sample.domain.TicketOrder;
import io.hrc.saga.orchestrator.sync.SynchronousSagaOrchestrator;

/** Sample stub: extends SynchronousSagaOrchestrator. TODO: implement for hcr-sample module. */
public abstract class TicketBookingOrchestrator
        extends SynchronousSagaOrchestrator<OrderRequest, TicketOrder> {

    protected TicketBookingOrchestrator(InventoryStrategy inventoryStrategy,
                                        PaymentGateway paymentGateway,
                                        EventBus eventBus) {
        super(inventoryStrategy, paymentGateway, eventBus);
    }
}
