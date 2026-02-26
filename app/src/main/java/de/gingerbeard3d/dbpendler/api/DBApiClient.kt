package de.gingerbeard3d.dbpendler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Client for Deutsche Bahn Hafas API
 * Uses the public transport.rest API (open source Hafas wrapper)
 */
class DBApiClient {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    // Using transport.rest - a public Hafas API wrapper
    // Alternative: DB's official API requires registration
    private val baseUrl = "https://v6.db.transport.rest"
    
    /**
     * Search for stations by name
     */
    suspend fun searchStations(query: String): Result<List<Station>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/locations?query=$encodedQuery&results=10&stops=true&addresses=false&poi=false"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                
                // Parse the response - transport.rest returns a list directly
                val stations = json.decodeFromString<List<TransportRestLocation>>(body)
                    .filter { it.type == "stop" || it.type == "station" }
                    .map { location ->
                        Station(
                            value = location.name,
                            id = location.id,
                            extId = location.id,
                            type = "ST"
                        )
                    }
                
                Result.success(stations)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Get departures from a station
     */
    suspend fun getDepartures(
        stationId: String,
        direction: String? = null,
        duration: Int = 60
    ): Result<List<Departure>> = withContext(Dispatchers.IO) {
        try {
            var url = "$baseUrl/stops/$stationId/departures?duration=$duration&results=10"
            if (direction != null) {
                url += "&direction=$direction"
            }
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                
                // Parse transport.rest departure format
                val departures = json.decodeFromString<List<TransportRestDeparture>>(body)
                    .mapNotNull { dep ->
                        try {
                            val plannedTime = LocalDateTime.parse(
                                dep.plannedWhen ?: dep.`when` ?: return@mapNotNull null,
                                DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            )
                            
                            Departure(
                                name = dep.line?.name ?: dep.line?.fahrtNr ?: "Unknown",
                                type = dep.line?.product ?: "",
                                stopid = stationId,
                                stop = dep.stop?.name ?: "",
                                time = plannedTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                                date = plannedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                                direction = dep.direction ?: "",
                                track = dep.plannedPlatform ?: dep.platform ?: "",
                                product = Product(
                                    name = dep.line?.name ?: "",
                                    catOut = dep.line?.product ?: ""
                                )
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                
                Result.success(departures)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Get connections between two stations
     */
    suspend fun getConnections(
        fromStationId: String,
        toStationId: String,
        departureTime: LocalDateTime = LocalDateTime.now()
    ): Result<List<Connection>> = withContext(Dispatchers.IO) {
        try {
            val timeStr = departureTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val url = "$baseUrl/journeys?from=$fromStationId&to=$toStationId&departure=$timeStr&results=5"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                
                val journeyResponse = json.decodeFromString<TransportRestJourneyResponse>(body)
                
                val connections = journeyResponse.journeys.mapNotNull { journey ->
                    try {
                        val firstLeg = journey.legs.firstOrNull() ?: return@mapNotNull null
                        val lastLeg = journey.legs.lastOrNull() ?: return@mapNotNull null
                        
                        val depTime = LocalDateTime.parse(
                            firstLeg.plannedDeparture ?: firstLeg.departure ?: return@mapNotNull null,
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        )
                        val arrTime = LocalDateTime.parse(
                            lastLeg.plannedArrival ?: lastLeg.arrival ?: return@mapNotNull null,
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        )
                        
                        val trainName = firstLeg.line?.name ?: "Unknown"
                        val trainIcon = when {
                            trainName.startsWith("ICE") -> "🚄"
                            trainName.startsWith("IC") || trainName.startsWith("EC") -> "🚃"
                            trainName.startsWith("RE") || trainName.startsWith("RB") -> "🚆"
                            trainName.startsWith("S") -> "🚈"
                            trainName.startsWith("U") -> "🚇"
                            else -> "🚂"
                        }
                        
                        Connection(
                            id = "${firstLeg.tripId}_${depTime}",
                            trainName = trainName,
                            trainIcon = trainIcon,
                            departureTime = depTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            arrivalTime = arrTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            departureStation = firstLeg.origin?.name ?: "",
                            arrivalStation = lastLeg.destination?.name ?: "",
                            platform = firstLeg.departurePlatform ?: "",
                            departureDateTime = depTime
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                
                Result.success(connections)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Transport.rest API models
@kotlinx.serialization.Serializable
data class TransportRestLocation(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val location: TransportRestGeo? = null
)

@kotlinx.serialization.Serializable
data class TransportRestGeo(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

@kotlinx.serialization.Serializable
data class TransportRestDeparture(
    val tripId: String? = null,
    val stop: TransportRestStop? = null,
    val `when`: String? = null,
    val plannedWhen: String? = null,
    val delay: Int? = null,
    val platform: String? = null,
    val plannedPlatform: String? = null,
    val direction: String? = null,
    val line: TransportRestLine? = null
)

@kotlinx.serialization.Serializable
data class TransportRestStop(
    val type: String = "",
    val id: String = "",
    val name: String = ""
)

@kotlinx.serialization.Serializable
data class TransportRestLine(
    val type: String = "",
    val id: String? = null,
    val fahrtNr: String? = null,
    val name: String = "",
    val product: String = ""
)

@kotlinx.serialization.Serializable
data class TransportRestJourneyResponse(
    val journeys: List<TransportRestJourney> = emptyList()
)

@kotlinx.serialization.Serializable
data class TransportRestJourney(
    val type: String = "",
    val legs: List<TransportRestLeg> = emptyList()
)

@kotlinx.serialization.Serializable
data class TransportRestLeg(
    val tripId: String? = null,
    val origin: TransportRestStop? = null,
    val destination: TransportRestStop? = null,
    val departure: String? = null,
    val plannedDeparture: String? = null,
    val arrival: String? = null,
    val plannedArrival: String? = null,
    val departurePlatform: String? = null,
    val arrivalPlatform: String? = null,
    val line: TransportRestLine? = null
)
