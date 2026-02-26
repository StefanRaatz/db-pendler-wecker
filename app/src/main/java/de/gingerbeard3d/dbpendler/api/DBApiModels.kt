package de.gingerbeard3d.dbpendler.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * DB API Response Models
 * Based on Deutsche Bahn Open API (Hafas)
 */

@Serializable
data class StationSearchResponse(
    val suggestions: List<Station> = emptyList()
)

@Serializable
data class Station(
    val value: String = "",      // Station name
    val id: String = "",         // EVA number
    val extId: String = "",      // External ID
    val type: String = "",       // Type (e.g., "ST" for station)
    val typeStr: String = "",    // Type string
    val xcoord: String = "",     // X coordinate
    val ycoord: String = "",     // Y coordinate
    val state: String = "",      // State
    val prodClass: String = "",  // Product class
    val weight: String = ""      // Weight/relevance
) {
    val displayName: String
        get() = value.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
}

@Serializable
data class DepartureBoard(
    @SerialName("Departure")
    val departures: List<Departure> = emptyList()
)

@Serializable
data class Departure(
    val name: String = "",           // Train name (e.g., "ICE 123")
    val type: String = "",           // Train type
    val stopid: String = "",         // Stop ID
    val stop: String = "",           // Stop name
    val time: String = "",           // Departure time (HH:mm:ss)
    val date: String = "",           // Date (YYYY-MM-DD)
    val direction: String = "",      // Direction/destination
    val track: String = "",          // Platform/track
    @SerialName("JourneyDetailRef")
    val journeyDetailRef: JourneyDetailRef? = null,
    @SerialName("Product")
    val product: Product? = null
) {
    val departureDateTime: LocalDateTime?
        get() = try {
            LocalDateTime.parse("${date}T${time}", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            null
        }
    
    val displayTime: String
        get() = time.substring(0, 5) // HH:mm
    
    val trainName: String
        get() = product?.name ?: name
    
    val trainIcon: String
        get() = when {
            name.startsWith("ICE") -> "🚄"
            name.startsWith("IC") || name.startsWith("EC") -> "🚃"
            name.startsWith("RE") || name.startsWith("RB") -> "🚆"
            name.startsWith("S") -> "🚈"
            name.startsWith("U") -> "🚇"
            else -> "🚂"
        }
}

@Serializable
data class JourneyDetailRef(
    val ref: String = ""
)

@Serializable
data class Product(
    val name: String = "",
    val num: String = "",
    val line: String = "",
    val catOut: String = "",
    val catIn: String = "",
    val catCode: String = "",
    val catOutS: String = "",
    val catOutL: String = "",
    val operatorCode: String = "",
    val operator: String = "",
    val admin: String = ""
)

@Serializable
data class JourneyDetails(
    @SerialName("Stops")
    val stops: StopsList? = null,
    @SerialName("Names")
    val names: NamesList? = null
)

@Serializable
data class StopsList(
    @SerialName("Stop")
    val stops: List<JourneyStop> = emptyList()
)

@Serializable
data class NamesList(
    @SerialName("Name")
    val names: List<JourneyName> = emptyList()
)

@Serializable
data class JourneyStop(
    val name: String = "",
    val id: String = "",
    val extId: String = "",
    val arrTime: String? = null,
    val arrDate: String? = null,
    val depTime: String? = null,
    val depDate: String? = null,
    val track: String? = null
) {
    val arrivalTime: String?
        get() = arrTime?.substring(0, 5)
    
    val departureTime: String?
        get() = depTime?.substring(0, 5)
}

@Serializable
data class JourneyName(
    val name: String = "",
    val number: String = "",
    val category: String = "",
    val routeIdxFrom: Int = 0,
    val routeIdxTo: Int = 0
)

/**
 * App-internal models
 */
data class Connection(
    val id: String,
    val trainName: String,
    val trainIcon: String,
    val departureTime: String,
    val arrivalTime: String,
    val departureStation: String,
    val arrivalStation: String,
    val platform: String,
    val departureDateTime: LocalDateTime
) {
    companion object {
        fun fromDeparture(departure: Departure, destinationStation: String, estimatedArrival: String): Connection {
            return Connection(
                id = "${departure.stopid}_${departure.time}_${departure.name}",
                trainName = departure.trainName,
                trainIcon = departure.trainIcon,
                departureTime = departure.displayTime,
                arrivalTime = estimatedArrival,
                departureStation = departure.stop,
                arrivalStation = destinationStation,
                platform = departure.track,
                departureDateTime = departure.departureDateTime ?: LocalDateTime.now()
            )
        }
    }
}

data class SavedStation(
    val id: String,
    val name: String,
    val displayName: String
)
