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
 * Client for Deutsche Bahn API via db-vendo-client REST service
 * Uses the community-maintained wrapper for accurate journey data
 * 
 * API Docs: https://github.com/public-transport/db-vendo-client
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
    
    // API endpoints
    // Station search: DB internal API (faster, works well)
    private val stationSearchUrl = "https://int.bahn.de/web/api/reiseloesung"
    // Journeys: transport.rest (accurate arrival times)
    private val journeysUrl = "https://v6.db.transport.rest"
    
    /**
     * Search for stations by name
     * Uses DB internal API (same as before, reliable)
     */
    suspend fun searchStations(query: String): Result<List<Station>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$stationSearchUrl/orte?suchbegriff=$encodedQuery&typ=ALL&limit=10"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                
                val locations = json.parseToJsonElement(body).jsonArray
                
                val stations = locations.mapNotNull { locElement ->
                    try {
                        val loc = locElement.jsonObject
                        val type = loc["type"]?.jsonPrimitive?.contentOrNull ?: ""
                        
                        // Only include stations (ST)
                        if (type != "ST") return@mapNotNull null
                        
                        val name = loc["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val extId = loc["extId"]?.jsonPrimitive?.contentOrNull ?: ""
                        val id = loc["id"]?.jsonPrimitive?.contentOrNull ?: extId
                        
                        Station(
                            value = name,
                            id = id,
                            extId = extId,
                            type = type
                        )
                    } catch (e: Exception) {
                        Log.e("DBApiClient", "Error parsing station: ${e.message}")
                        null
                    }
                }
                
                Log.d("DBApiClient", "Found ${stations.size} stations for query")
                Result.success(stations)
            }
        } catch (e: Exception) {
            Log.e("DBApiClient", "searchStations error: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get connections between two stations using /journeys endpoint
     * Returns accurate departure and arrival times
     */
    suspend fun getConnections(
        fromStationId: String,
        toStationId: String,
        departureTime: LocalDateTime = LocalDateTime.now()
    ): Result<List<Connection>> = withContext(Dispatchers.IO) {
        try {
            val fromId = extractStationId(fromStationId)
            val toId = extractStationId(toStationId)
            
            // Format time for API: ISO 8601 with timezone
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            val zonedTime = departureTime.atZone(ZoneId.of("Europe/Berlin"))
            val timeStr = URLEncoder.encode(zonedTime.format(formatter), "UTF-8")
            
            val url = "$journeysUrl/journeys?from=$fromId&to=$toId&departure=$timeStr&results=10"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                val jsonResponse = json.parseToJsonElement(body).jsonObject
                
                val journeys = jsonResponse["journeys"]?.jsonArray 
                    ?: return@withContext Result.success(emptyList())
                
                val connections = journeys.mapNotNull { journeyElement ->
                    try {
                        val journey = journeyElement.jsonObject
                        val legs = journey["legs"]?.jsonArray ?: return@mapNotNull null
                        
                        // For direct connections, use first leg
                        // TODO: Handle transfers (multiple legs)
                        val firstLeg = legs.firstOrNull()?.jsonObject ?: return@mapNotNull null
                        val lastLeg = legs.lastOrNull()?.jsonObject ?: firstLeg
                        
                        // Parse departure and arrival times
                        val depTimeStr = firstLeg["departure"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val arrTimeStr = lastLeg["arrival"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        
                        val depTime = parseIsoDateTime(depTimeStr)
                        val arrTime = parseIsoDateTime(arrTimeStr)
                        
                        // Skip if departure is in the past
                        if (depTime.isBefore(departureTime)) {
                            return@mapNotNull null
                        }
                        
                        // Get line info
                        val line = firstLeg["line"]?.jsonObject
                        val lineName = line?.get("name")?.jsonPrimitive?.content 
                            ?: line?.get("productName")?.jsonPrimitive?.content
                            ?: "Zug"
                        
                        val productName = line?.get("productName")?.jsonPrimitive?.content ?: ""
                        
                        // Get platform
                        val platform = firstLeg["departurePlatform"]?.jsonPrimitive?.content 
                            ?: firstLeg["plannedDeparturePlatform"]?.jsonPrimitive?.content
                            ?: ""
                        
                        // Get station names
                        val originObj = firstLeg["origin"]?.jsonObject
                        val destObj = lastLeg["destination"]?.jsonObject
                        
                        val originName = originObj?.get("name")?.jsonPrimitive?.content
                            ?: originObj?.get("station")?.jsonObject?.get("name")?.jsonPrimitive?.content
                            ?: "Start"
                        val destName = destObj?.get("name")?.jsonPrimitive?.content
                            ?: destObj?.get("station")?.jsonObject?.get("name")?.jsonPrimitive?.content
                            ?: "Ziel"
                        
                        // Get tripId
                        val tripId = firstLeg["tripId"]?.jsonPrimitive?.content ?: "${depTime}_$lineName"
                        
                        // Determine train icon
                        val trainIcon = getTrainIcon(lineName, productName)
                        
                        // Check for transfers
                        val hasTransfer = legs.size > 1
                        val displayName = if (hasTransfer) {
                            "$lineName +${legs.size - 1}"
                        } else {
                            lineName
                        }
                        
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
                        null
                    }
                }
                
                // Return first 5 direct or minimal-transfer connections
                Result.success(connections.take(5))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Parse ISO 8601 datetime string to LocalDateTime
     * e.g., "2026-02-27T07:19:00+01:00"
     */
    private fun parseIsoDateTime(isoString: String): LocalDateTime {
        return try {
            // Parse with offset and convert to local
            val zonedDateTime = java.time.ZonedDateTime.parse(isoString)
            zonedDateTime.withZoneSameInstant(ZoneId.of("Europe/Berlin")).toLocalDateTime()
        } catch (e: Exception) {
            // Fallback: try without offset
            LocalDateTime.parse(isoString.substringBefore("+").substringBefore("Z"))
        }
    }
    
    /**
     * Extract station ID from full station ID string
     * Input: "A=1@O=Friedrichshafen Stadt@X=9473902@Y=47653220@U=80@L=8000112@..."
     * Output: "8000112"
     */
    private fun extractStationId(stationId: String): String {
        // If it's already just a number, return it
        if (stationId.matches(Regex("\\d+"))) {
            return stationId
        }
        
        // Extract L= parameter (the EVA number)
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
