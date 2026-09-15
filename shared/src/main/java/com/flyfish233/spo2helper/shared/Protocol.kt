package com.flyfish233.spo2helper.shared

/**
 * Wearable Data Layer message paths shared by the phone and watch apps.
 * Both apps must ship with the same applicationId and signing key for
 * messages to be delivered between them.
 */
object Protocol {
    /** Phone -> watch: run a spot check now. Empty payload. */
    const val PATH_MEASURE = "/spo2/measure"

    /** Watch -> phone: result of a spot check. Payload is [Reading.toJson]. */
    const val PATH_RESULT = "/spo2/result"

    /** Phone -> watch: channel assignment and calibration. Payload is [Spo2Config.toJson]. */
    const val PATH_CONFIG = "/spo2/config"
}
