/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.invoice.generator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.LocalDate;
import org.joda.time.Months;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.killbill.billing.ErrorCode;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.clock.Clock;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.model.BillingMode;
import org.killbill.billing.invoice.model.DefaultInvoice;
import org.killbill.billing.invoice.model.FixedPriceInvoiceItem;
import org.killbill.billing.invoice.model.InAdvanceBillingMode;
import org.killbill.billing.invoice.model.InvalidDateSequenceException;
import org.killbill.billing.invoice.model.RecurringInvoiceItem;
import org.killbill.billing.invoice.model.RecurringInvoiceItemData;
import org.killbill.billing.invoice.tree.AccountItemTree;
import org.killbill.billing.junction.BillingEvent;
import org.killbill.billing.junction.BillingEventSet;
import org.killbill.billing.junction.BillingModeType;
import org.killbill.billing.util.config.InvoiceConfig;
import org.killbill.billing.util.currency.KillBillMoney;

import com.google.inject.Inject;

public class DefaultInvoiceGenerator implements InvoiceGenerator {

    private static final Logger log = LoggerFactory.getLogger(DefaultInvoiceGenerator.class);

    private final Clock clock;
    private final InvoiceConfig config;

    @Inject
    public DefaultInvoiceGenerator(final Clock clock, final InvoiceConfig config) {
        this.clock = clock;
        this.config = config;
    }

    /*
     * adjusts target date to the maximum invoice target date, if future invoices exist
     */
    @Override
    public Invoice generateInvoice(final UUID accountId, @Nullable final BillingEventSet events,
                                   @Nullable final List<Invoice> existingInvoices,
                                   final LocalDate targetDate,
                                   final Currency targetCurrency) throws InvoiceApiException {
        if ((events == null) || (events.size() == 0) || events.isAccountAutoInvoiceOff()) {
            return null;
        }

        validateTargetDate(targetDate);
        final LocalDate adjustedTargetDate = adjustTargetDate(existingInvoices, targetDate);

        final Invoice invoice = new DefaultInvoice(accountId, clock.getUTCToday(), adjustedTargetDate, targetCurrency);
        final UUID invoiceId = invoice.getId();

        final List<InvoiceItem> inAdvanceItems = generateInAdvanceInvoiceItems(accountId, invoiceId, events, existingInvoices, adjustedTargetDate, targetCurrency);
        invoice.addInvoiceItems(inAdvanceItems);

        return inAdvanceItems.size() != 0 ? invoice : null;
    }

    private List<InvoiceItem> generateInAdvanceInvoiceItems(final UUID accountId, final UUID invoiceId, @Nullable final BillingEventSet events,
                                               @Nullable final List<Invoice> existingInvoices, final LocalDate targetDate,
                                               final Currency targetCurrency) throws InvoiceApiException {
        final AccountItemTree accountItemTree = new AccountItemTree(accountId);
        if (existingInvoices != null) {
            for (final Invoice invoice : existingInvoices) {
                for (final InvoiceItem item : invoice.getInvoiceItems()) {
                    if (item.getSubscriptionId() == null || // Always include migration invoices, credits, external charges etc.
                        !events.getSubscriptionIdsWithAutoInvoiceOff()
                               .contains(item.getSubscriptionId())) { //don't add items with auto_invoice_off tag
                        accountItemTree.addExistingItem(item);
                    }
                }
            }
        }

        // Generate list of proposed invoice items based on billing events from junction-- proposed items are ALL items since beginning of time
        final List<InvoiceItem> proposedItems = generateInAdvanceInvoiceItems(invoiceId, accountId, events, targetDate, targetCurrency);

        accountItemTree.mergeWithProposedItems(proposedItems);
        return accountItemTree.getResultingItemList();
    }

    private void validateTargetDate(final LocalDate targetDate) throws InvoiceApiException {
        final int maximumNumberOfMonths = config.getNumberOfMonthsInFuture();

        if (Months.monthsBetween(clock.getUTCToday(), targetDate).getMonths() > maximumNumberOfMonths) {
            throw new InvoiceApiException(ErrorCode.INVOICE_TARGET_DATE_TOO_FAR_IN_THE_FUTURE, targetDate.toString());
        }
    }

    private LocalDate adjustTargetDate(final List<Invoice> existingInvoices, final LocalDate targetDate) {
        if (existingInvoices == null) {
            return targetDate;
        }

        LocalDate maxDate = targetDate;

        for (final Invoice invoice : existingInvoices) {
            if (invoice.getTargetDate().isAfter(maxDate)) {
                maxDate = invoice.getTargetDate();
            }
        }
        return maxDate;
    }

    private List<InvoiceItem> generateInAdvanceInvoiceItems(final UUID invoiceId, final UUID accountId, final BillingEventSet events,
                                                            final LocalDate targetDate, final Currency currency) throws InvoiceApiException {
        final List<InvoiceItem> items = new ArrayList<InvoiceItem>();

        if (events.size() == 0) {
            return items;
        }

        // Pretty-print the generated invoice items from the junction events
        final StringBuilder logStringBuilder = new StringBuilder("Invoice items generated for invoiceId ")
                .append(invoiceId)
                .append(" and accountId ")
                .append(accountId);

        final Iterator<BillingEvent> eventIt = events.iterator();
        BillingEvent nextEvent = eventIt.next();
        while (eventIt.hasNext()) {
            final BillingEvent thisEvent = nextEvent;
            nextEvent = eventIt.next();
            if (!events.getSubscriptionIdsWithAutoInvoiceOff().
                    contains(thisEvent.getSubscription().getId())) { // don't consider events for subscriptions that have auto_invoice_off
                final BillingEvent adjustedNextEvent = (thisEvent.getSubscription().getId() == nextEvent.getSubscription().getId()) ? nextEvent : null;
                items.addAll(processInAdvanceEvents(invoiceId, accountId, thisEvent, adjustedNextEvent, targetDate, currency, logStringBuilder));
            }
        }
        items.addAll(processInAdvanceEvents(invoiceId, accountId, nextEvent, null, targetDate, currency, logStringBuilder));

        log.info(logStringBuilder.toString());

        return items;
    }

    // Turn a set of events into a list of invoice items. Note that the dates on the invoice items will be rounded (granularity of a day)
    private List<InvoiceItem> processInAdvanceEvents(final UUID invoiceId, final UUID accountId, final BillingEvent thisEvent, @Nullable final BillingEvent nextEvent,
                                                     final LocalDate targetDate, final Currency currency,
                                                     final StringBuilder logStringBuilder) throws InvoiceApiException {
        final List<InvoiceItem> items = new ArrayList<InvoiceItem>();

        // Handle fixed price items
        final InvoiceItem fixedPriceInvoiceItem = generateFixedPriceItem(invoiceId, accountId, thisEvent, targetDate, currency);
        if (fixedPriceInvoiceItem != null) {
            items.add(fixedPriceInvoiceItem);
        }

        // Handle recurring items
        final BillingPeriod billingPeriod = thisEvent.getBillingPeriod();
        if (billingPeriod != BillingPeriod.NO_BILLING_PERIOD) {
            final BillingMode billingMode = instantiateBillingMode(thisEvent.getBillingMode());
            final LocalDate startDate = new LocalDate(thisEvent.getEffectiveDate(), thisEvent.getTimeZone());

            if (!startDate.isAfter(targetDate)) {
                final LocalDate endDate = (nextEvent == null) ? null : new LocalDate(nextEvent.getEffectiveDate(), nextEvent.getTimeZone());

                final int billCycleDayLocal = thisEvent.getBillCycleDayLocal();

                final List<RecurringInvoiceItemData> itemData;
                try {
                    itemData = billingMode.calculateInvoiceItemData(startDate, endDate, targetDate, billCycleDayLocal, billingPeriod);
                } catch (InvalidDateSequenceException e) {
                    throw new InvoiceApiException(ErrorCode.INVOICE_INVALID_DATE_SEQUENCE, startDate, endDate, targetDate);
                }

                for (final RecurringInvoiceItemData itemDatum : itemData) {
                    final BigDecimal rate = thisEvent.getRecurringPrice();

                    if (rate != null) {
                        final BigDecimal amount = KillBillMoney.of(itemDatum.getNumberOfCycles().multiply(rate), currency);

                        final RecurringInvoiceItem recurringItem = new RecurringInvoiceItem(invoiceId,
                                                                                            accountId,
                                                                                            thisEvent.getSubscription().getBundleId(),
                                                                                            thisEvent.getSubscription().getId(),
                                                                                            thisEvent.getPlan().getName(),
                                                                                            thisEvent.getPlanPhase().getName(),
                                                                                            itemDatum.getStartDate(), itemDatum.getEndDate(),
                                                                                            amount, rate, currency);
                        items.add(recurringItem);
                    }
                }
            }
        }

        // For debugging purposes
        logStringBuilder.append("\n")
                        .append(thisEvent);
        for (final InvoiceItem item : items) {
            logStringBuilder.append("\n\t")
                            .append(item);
        }

        return items;
    }

    private BillingMode instantiateBillingMode(final BillingModeType billingMode) {
        switch (billingMode) {
            case IN_ADVANCE:
                return new InAdvanceBillingMode();
            default:
                throw new UnsupportedOperationException();
        }
    }

    InvoiceItem generateFixedPriceItem(final UUID invoiceId, final UUID accountId, final BillingEvent thisEvent,
                                       final LocalDate targetDate, final Currency currency) {
        final LocalDate roundedStartDate = new LocalDate(thisEvent.getEffectiveDate(), thisEvent.getTimeZone());

        if (roundedStartDate.isAfter(targetDate)) {
            return null;
        } else {
            final BigDecimal fixedPrice = thisEvent.getFixedPrice();

            if (fixedPrice != null) {
                return new FixedPriceInvoiceItem(invoiceId, accountId, thisEvent.getSubscription().getBundleId(),
                                                 thisEvent.getSubscription().getId(),
                                                 thisEvent.getPlan().getName(), thisEvent.getPlanPhase().getName(),
                                                 roundedStartDate, fixedPrice, currency);
            } else {
                return null;
            }
        }
    }
}