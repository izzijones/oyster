package com.tfl.billing;

import com.oyster.*;
import com.tfl.external.Customer;
import com.tfl.external.CustomerDatabase;
import com.tfl.external.PaymentsSystem;

import java.math.BigDecimal;
import java.util.*;

public class TravelTracker implements ScanListener {

    static final BigDecimal OFF_PEAK_SHORT_JOURNEY_PRICE = new BigDecimal(1.60);
    static final BigDecimal OFF_PEAK_LONG_JOURNEY_PRICE = new BigDecimal(2.70);
    static final BigDecimal PEAK_SHORT_JOURNEY_PRICE = new BigDecimal(2.90);
    static final BigDecimal PEAK_LONG_JOURNEY_PRICE = new BigDecimal(3.80);

    static final BigDecimal OFF_PEAK_CAP = new BigDecimal(7.0);
    static final BigDecimal PEAK_CAP = new BigDecimal(9.0);

    private final List<JourneyEvent> eventLog = new ArrayList<JourneyEvent>();
    private final Set<UUID> currentlyTravelling = new HashSet<UUID>();

    public void chargeAccounts() {
        CustomerDatabase customerDatabase = CustomerDatabase.getInstance();

        List<Customer> customers = customerDatabase.getCustomers();
        for (Customer customer : customers) {
            totalJourneysFor(customer);
        }
    }

    private void totalJourneysFor(Customer customer) {
        List<JourneyEvent> customerJourneyEvents = new ArrayList<JourneyEvent>();
        for (JourneyEvent journeyEvent : eventLog) {
            if (journeyEvent.cardId().equals(customer.cardId())) {
                customerJourneyEvents.add(journeyEvent);
            }
        }

        List<Journey> journeys = new ArrayList<Journey>();

        JourneyEvent start = null;
        for (JourneyEvent event : customerJourneyEvents) {
            if (event instanceof JourneyStart) {
                start = event;
            }
            if (event instanceof JourneyEnd && start != null) {
                journeys.add(new Journey(start, event));
                start = null;
            }
        }

        int offPeakCount = 0;
        int peakCount = 0;
        BigDecimal customerTotal = new BigDecimal(0);
        for (Journey journey : journeys) {
            BigDecimal journeyPrice;
            if (peak(journey)&&isLong(journey)) {
                journeyPrice = PEAK_LONG_JOURNEY_PRICE;
                peakCount++;
            }
            else if(peak(journey)&& !isLong(journey)){
                journeyPrice = PEAK_SHORT_JOURNEY_PRICE;
                peakCount++;
            }
            else if(!peak(journey)&&isLong(journey)){
                journeyPrice = OFF_PEAK_LONG_JOURNEY_PRICE;
                offPeakCount++;
            }
            else{
                journeyPrice = OFF_PEAK_SHORT_JOURNEY_PRICE;
                offPeakCount++;
            }
            customerTotal = customerTotal.add(journeyPrice);
            if(journeys.size()==offPeakCount && (customerTotal.compareTo(OFF_PEAK_CAP) > 0 )){
                    customerTotal = OFF_PEAK_CAP;
            }
            else if(customerTotal.compareTo(PEAK_CAP)>0){
                customerTotal = PEAK_CAP;
            }
        }

        PaymentsSystem.getInstance().charge(customer, journeys, roundToNearestPenny(customerTotal));
    }

    private BigDecimal roundToNearestPenny(BigDecimal poundsAndPence) {
        return poundsAndPence.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private boolean peak(Journey journey) {
        return peak(journey.startTime()) || peak(journey.endTime());
    }

    private boolean peak(Date time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(time);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return (hour >= 6 && hour <= 9) || (hour >= 17 && hour <= 19);
    }

    private boolean isLong(Journey journey){
        return (journey.durationSeconds() > 25*60);
    }

    public void connect(OysterCardReader... cardReaders) {
        for (OysterCardReader cardReader : cardReaders) {
            cardReader.register(this);
        }
    }

    @Override
    public void cardScanned(UUID cardId, UUID readerId) {
        if (currentlyTravelling.contains(cardId)) {
            eventLog.add(new JourneyEnd(cardId, readerId));
            currentlyTravelling.remove(cardId);
            System.out.println("Journey ended for " + cardId.toString());
        } else {
            if (CustomerDatabase.getInstance().isRegisteredId(cardId)) {
                currentlyTravelling.add(cardId);
                eventLog.add(new JourneyStart(cardId, readerId));
                System.out.println("Journey started for "+ cardId.toString());
            } else {
                throw new UnknownOysterCardException(cardId);
            }
        }
    }

}
