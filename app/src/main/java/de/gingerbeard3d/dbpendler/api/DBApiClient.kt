package de.gingerbeard3d.dbpendler.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Client for Deutsche Bahn API via self-hosted db-vendo-client
 * 
 * API runs on internal network (accessible via VPN)
 * Source: https://github.com/public-transport/db-vendo-client
 */
class DBApiClient {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    // Self-hosted db-vendo-client API (internal network, VPN required)
    private val baseUrl = "http://192.168.128.70:3000"
    
    /**
     * Search for stations by name
     */
    suspend fun searchStations(query: String): Result<List<Station>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/locations?query=$encodedQuery&results=10&stations=true&poi=false&addresses=false"
            
            Log.d("DBApiClient", "Searching stations: $url")
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("DBApiClient", "Station search failed: ${response.code}")
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() 
                    ?: return@withContext Result.failure(Exception("Empty response"))
                
                val locations = json.parseToJsonElement(body).jsonArray
                
                val stations = locations.mapNotNull { locElement ->
                    try {
                        val loc = locElement.jsonObject
                        val type = loc["type"]?.jsonPrimitive?.contentOrNull ?: ""
                        
                        // Include stations and stops
                        if (type != "station" && type != "stop") return@mapNotNull null
                        
                        val name = loc["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val id = loc["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        
                        // For stops, prefer parent station ID
                        val parentId = loc["station"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                        
                        Station(
                            value = name,
                            id = parentId ?: id,
                            extId = id,
                            type = type
                        )
                    } catch (e: Exception) {
                        Log.e("DBApiClient", "Error parsing station: ${e.message}")
                        null
                    }
                }
                
                Log.d("DBApiClient", "Found ${stations.size} stations")
                Result.success(stations)
            }
        } catch (e: Exception) {
            Log.e("DBApiClient", "searchStations error: ${e.message}", e)
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
            val fromId = extractStationId(fromStationId)
            val toId = extractStationId(toStationId)
            
            // Format time: ISO 8601 with timezone
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            val zonedTime = departureTime.atZone(ZoneId.of("Europe/Berlin"))
            val timeStr = URLEncoder.encode(zonedTime.format(formatter), "UTF-8")
            
            val url = "$baseUrl/journeys?from=$fromId&to=$toId&departure=$timeStr&results=10"
            
            Log.d("DBApiClient", "Getting journeys: $url")
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("DBApiClient", "Journeys request failed: ${response.code}")
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() 
                    ?: return@withContext Result.failure(Exception("Empty response"))
                
                val jsonResponse = json.parseToJsonElement(body).jsonObject
                val journeys = jsonResponse["journeys"]?.jsonArray 
                    ?: return@withContext Result.success(emptyList())
                
                val connections = journeys.mapNotNull { journeyElement ->
                    parseJourney(journeyElement, departureTime)
                }
                
                Log.d("DBApiClient", "Found ${connections.size} connections")
                Result.success(connections.take(5))
            }
        } catch (e: Exception) {
            Log.e("DBApiClient", "getConnections error: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    private fun parseJourney(journeyElement: JsonElement, departureTime: LocalDateTime): Connection? {
        return try {
            val journey = journeyElement.jsonObject
            val legs = journey["legs"]?.jsonArray ?: return null
            
            val firstLeg = legs.firstOrNull()?.jsonObject ?: return null
            val lastLeg = legs.lastOrNull()?.jsonObject ?: firstLeg
            
            // Parse times
            val depTimeStr = firstLeg["departure"]?.jsonPrimitive?.contentOrNull ?: return null
            val arrTimeStr = lastLeg["arrival"]?.jsonPrimitive?.contentOrNull ?: return null
            
            val depTime = parseIsoDateTime(depTimeStr)
            val arrTime = parseIsoDateTime(arrTimeStr)
            
            // Skip past departures
            if (depTime.isBefore(departureTime)) return null
            
            // Line info
            val line = firstLeg["line"]?.jsonObject
            val lineName = line?.get("name")?.jsonPrimitive?.contentOrNull 
                ?: line?.get("productName")?.jsonPrimitive?.contentOrNull
                ?: "Zug"
            val productName = line?.get("productName")?.jsonPrimitive?.contentOrNull ?: ""
            
            // Platform
            val platform = firstLeg["departurePlatform"]?.jsonPrimitive?.contentOrNull 
                ?: firstLeg["plannedDeparturePlatform"]?.jsonPrimitive?.contentOrNull
                ?: ""
            
            // Station names
            val originObj = firstLeg["origin"]?.jsonObject
            val destObj = lastLeg["destination"]?.jsonObject
            
            val originName = originObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: originObj?.get("station")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                ?: "Start"
            val destName = destObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: destObj?.get("station")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                ?: "Ziel"
            
            val tripId = firstLeg["tripId"]?.jsonPrimitive?.contentOrNull ?: "${depTime}_$lineName"
            val trainIcon = getTrainIcon(lineName, productName)
            
            // Handle transfers
            val hasTransfer = legs.size > 1
            val displayName = if (hasTransfer) "$lineName +${legs.size - 1}" else lineName
            
            Connection(
                id = tripId,
                trainName = displayName,
                trainIcon = trainIcon,
                departureTime = depTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                arrivalTime = arrTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                departureStation = originName,
                arrivalStation = destName,
                platform = platform,
                departureDateTime = depTime
            )
        } catch (e: Exception) {
            Log.e("DBApiClient", "Error parsing journey: ${e.message}")
            null
        }
    }
    
    private fun parseIsoDateTime(isoString: String): LocalDateTime {
        return try {
            val zonedDateTime = java.time.ZonedDateTime.parse(isoString)
            zonedDateTime.withZoneSameInstant(ZoneId.of("Europe/Berlin")).toLocalDateTime()
        } catch (e: Exception) {
            LocalDateTime.parse(isoString.substringBefore("+").substringBefore("Z"))
        }
    }
    
    private fun extractStationId(stationId: String): String {
        if (stationId.matches(Regex("\\d+"))) return stationId
        val match = Regex("L=(\\d+)").find(stationId)
        return match?.groupValues?.get(1) ?: stationId
    }
    
    private fun getTrainIcon(name: String, productName: String): String {
        return when {
            name.startsWith("ICE") || productName.contains("ICE", ignoreCase = true) -> "🚄"
            name.startsWith("IC") || name.startsWith("EC") -> "🚃"
            name.startsWith("RE") || name.startsWith("RB") -> "🚆"
            name.startsWith("S") && name.length <= 3 -> "🚈"
            name.startsWith("U") -> "🚇"
            productName.contains("Bus", ignoreCase = true) || name.contains("Bus") -> "🚌"
            else -> "🚂"
        }
    }
}
