package com.knode.search.rebuild.es;

public record AliasSwitchResult(Status status, int httpStatus, String detail) {

    public static AliasSwitchResult acknowledged() {
        return new AliasSwitchResult(Status.ACKNOWLEDGED, 200, "ACKNOWLEDGED");
    }

    public static AliasSwitchResult confirmedPreSwitchRejected(int httpStatus, String detail) {
        return new AliasSwitchResult(Status.CONFIRMED_PRE_SWITCH_REJECTED, httpStatus, detail);
    }

    public static AliasSwitchResult partialOrUnacknowledged(int httpStatus, String detail) {
        return new AliasSwitchResult(Status.PARTIAL_OR_UNACKNOWLEDGED, httpStatus, detail);
    }

    public static AliasSwitchResult uncertainInFlight(int httpStatus, String detail) {
        return new AliasSwitchResult(Status.UNCERTAIN_IN_FLIGHT, httpStatus, detail);
    }

    public boolean isAcknowledged() {
        return status == Status.ACKNOWLEDGED;
    }

    public boolean isConfirmedPreSwitchRejected() {
        return status == Status.CONFIRMED_PRE_SWITCH_REJECTED;
    }

    public enum Status {
        ACKNOWLEDGED,
        CONFIRMED_PRE_SWITCH_REJECTED,
        PARTIAL_OR_UNACKNOWLEDGED,
        UNCERTAIN_IN_FLIGHT
    }
}
