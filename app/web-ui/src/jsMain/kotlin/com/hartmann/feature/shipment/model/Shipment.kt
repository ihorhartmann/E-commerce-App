package com.hartmann.feature.shipment.model

/** [line1]/[line2] are blank unless the shipment is assigned to the calling courier - shipping hides the street. */
data class Shipment(
    val id: Long,
    val orderId: Long,
    val city: String,
    val postalCode: String,
    val line1: String,
    val line2: String,
    val status: ShipmentStatus,
    val holder: ShipmentHolder,
    val cancellationReason: ShipmentCancellationReason?,
) {
    fun canBePickedUp(): Boolean = status == ShipmentStatus.CREATED

    /** Delivered, lost or cancelled at the door: only while the courier is on the way with it. */
    fun isInDelivery(): Boolean = status == ShipmentStatus.IN_DELIVERY

    /** A cancelled shipment whose goods the courier still has to bring back. */
    fun awaitsReturn(): Boolean = status == ShipmentStatus.CANCELLED && holder == ShipmentHolder.COURIER
}

/** The shipping-service's own spelling of each status is kept in [apiName] ("In Delivery" has a space). */
enum class ShipmentStatus(val apiName: String, val label: String) {
    CREATED("Created", "Ready for pickup"),
    IN_DELIVERY("In Delivery", "In delivery"),
    DELIVERED("Delivered", "Delivered"),
    CANCELLED("Cancelled", "Cancelled"),
    LOST("Lost", "Lost");

    companion object {
        fun fromApiName(apiName: String): ShipmentStatus? = entries.firstOrNull { it.apiName == apiName }
    }
}

enum class ShipmentHolder(val apiName: String) {
    INVENTORY("Inventory"),
    COURIER("Courier");

    companion object {
        fun fromApiName(apiName: String): ShipmentHolder? = entries.firstOrNull { it.apiName == apiName }
    }
}

enum class ShipmentCancellationReason(val apiName: String, val label: String, val reportedByCourier: Boolean) {
    ORDER_CANCELLED("OrderCancelled", "Order cancelled", false),
    RECIPIENT_REFUSED("RecipientRefused", "Recipient refused", true),
    RECIPIENT_UNAVAILABLE("RecipientUnavailable", "Recipient unavailable", true);

    companion object {
        fun fromApiName(apiName: String): ShipmentCancellationReason? = entries.firstOrNull { it.apiName == apiName }

        /** The reasons a courier can give at the door; an order cancellation reaches the shipment on its own. */
        fun courierReasons(): List<ShipmentCancellationReason> = entries.filter { it.reportedByCourier }
    }
}
