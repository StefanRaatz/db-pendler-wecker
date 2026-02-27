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
 * Client for Deutsche Bahn API
 * Uses hybrid approach: DB internal API for stations, transport.rest for journeys
 * Falls back to DB API if transport.rest is unavailable
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
    
    // API endpoints
    private val dbApiUrl = "https://int.bahn.de/web/api/reiseloesung"
    private val transportRestUrl = "https://v6.db.transport.rest"
    
    /**
     * Search for stations by name
     * Uses DB internal API (reliable)
     */
    suspend fun searchStations(query: String): Result<List<Station>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$dbApiUrl/orte?suchbegriff=$encodedQuery&typ=ALL&limit=10"
            
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
                        
                        if (type != "ST") return@mapNotNull null
                        
                        val name = loc["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val extId = loc["extId"]?.jsonPrimitive?.contentOrNull ?: ""
                        val id = loc["id"]?.jsonPrimitive?.contentOrNull ?: extId
                        
                        Station(value = name, id = id, extId = extId, type = type)
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
     * Tries transport.rest first, falls back to DB API
     */
    suspend fun getConnections(
        fromStationId: String,
        toStationId: String,
        departureTime: LocalDateTime = LocalDateTime.now()
    ): Result<List<Connection>> = withContext(Dispatchers.IO) {
        // Try transport.rest first (accurate arrival times)
        val result = tryTransportRest(fromStationId, toStationId, departureTime)
        if (result.isSuccess && result.getOrNull()?.isNotEmpty() == true) {
            return@withContext result
        }
        
        // Fallback to DB API (estimated arrival times)
        Log.w("DBApiClient", "transport.rest failed or empty, using DB API fallback")
        tryDbApiFallback(fromStationId, toStationId, departureTime)
    }
    
    private fun tryTransportRest(
        fromStationId: String,
        toStationId: String,
        departureTime: LocalDateTime
    ): Result<List<Connection>> {
        return try {
            val fromId = extractStationId(fromStationId)
            val toId = extractStationId(toStationId)
            
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            val zonedTime = departureTime.atZone(ZoneId.of("Europe/Berlin"))
            val timeStr = URLEncoder.encode(zonedTime.format(formatter), "UTF-8")
            
            val url = "$transportRestUrl/journeys?from=$fromId&to=$toId&departure=$timeStr&results=10"
            Log.d("DBApiClient", "Trying transport.rest: $url")
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("DBApiClient", "transport.rest returned ${response.code}")
                    return Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() 
                    ?: return Result.failure(Exception("Empty response"))
                
                val jsonResponse = json.parseToJsonElement(body).jsonObject
                val journeys = jsonResponse["journeys"]?.jsonArray 
                    ?: return Result.success(emptyList())
                
                val connections = parseJourneys(journeys, departureTime, fromStationId, toStationId)
                Log.d("DBApiClient", "transport.rest: Found ${connections.size} connections")
                Result.success(connections.take(5))
            }
        } catch (e: Exception) {
            Log.e("DBApiClient", "transport.rest error: ${e.message}")
            Result.failure(e)
        }
    }
    
    private fun tryDbApiFallback(
        fromStationId: String,
        toStationId: String,
        departureTime: LocalDateTime
    ): Result<List<Connection>> {
        return try {
            val fromExtId = extractStationId(fromStationId)
            val toExtId = extractStationId(toStationId)
            
            val dateStr = departureTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val timeStr = departureTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            
            val url = "$dbApiUrl/abfahrten?ortExtId=$fromExtId&datum=$dateStr&zeit=$timeStr"
            Log.d("DBApiClient", "Using DB API fallback: $url")
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("API error: ${response.code}"))
                }
                
                val body = response.body?.string() 
                    ?: return Result.failure(Exception("Empty response"))
                
                val jsonResponse = json.parseToJsonElement(body).jsonObject
                val entries = jsonResponse["entries"]?.jsonArray 
                    ?: return Result.success(emptyList())
                
                val toStationName = getStationNameFromId(toStationId)
                val connections = parseDepartures(entries, departureTime, fromStationId, toStationId, toStationName, toExtId)
                Log.d("DBApiClient", "DB API fallback: Found ${connections.size} connections")
                Result.success(connections.take(5))
            }
        } catch (e: Exception) {
            Log.e("DBApiClient", "DB API fallback error: ${e.message}")
            Result.failure(e)
        }
    }
    
    private fun parseJourneys(
        journeys: JsonArray,
        departureTime: LocalDateTime,
        fromStationId: String,
        toStationId: String
    ): List<Connection> {
        return journeys.mapNotNull { journeyElement ->
            try {
                val journey = journeyElement.jsonObject
                val legs = journey["legs"]?.jsonArray ?: return@mapNotNull null
                
                val firstLeg = legs.firstOrNull()?.jsonObject ?: return@mapNotNull null
                val lastLeg = legs.lastOrNull()?.jsonObject ?: firstLeg
                
                val depTimeStr = firstLeg["departure"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val arrTimeStr = lastLeg["arrival"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                
                val depTime = parseIsoDateTime(depTimeStr)
                val arrTime = parseIsoDateTime(arrTimeStr)
                
                if (depTime.isBefore(departureTime)) return@mapNotNull null
                
                val line = firstLeg["line"]?.jsonObject
                val lineName = line?.get("name")?.jsonPrimitive?.contentOrNull 
                    ?: line?.get("productName")?.jsonPrimitive?.contentOrNull
                    ?: "Zug"
                val productName = line?.get("productName")?.jsonPrimitive?.contentOrNull ?: ""
                
                val platform = firstLeg["departurePlatform"]?.jsonPrimitive?.contentOrNull 
                    ?: firstLeg["plannedDeparturePlatform"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                
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
    }
    
    private fun parseDepartures(
        entries: JsonArray,
        departureTime: LocalDateTime,
        fromStationId: String,
        toStationId: String,
        toStationName: String,
        toExtId: String
    ): List<Connection> {
        return entries.mapNotNull { entryElement ->
            try {
                val entry = entryElement.jsonObject
                
                val verkehrsmittel = entry["verkehrmittel"]?.jsonObject
                val produktGattung = verkehrsmittel?.get("produktGattung")?.jsonPrimitive?.contentOrNull ?: ""
                
                // Only trains
                if (produktGattung !in listOf("REGIONAL", "ICE", "EC_IC", "IR", "SBAHN")) {
                    return@mapNotNull null
                }
                
                val zeitStr = entry["zeit"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val depTime = LocalDateTime.parse(zeitStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                
                if (depTime.isBefore(departureTime)) return@mapNotNull null
                
                val trainName = verkehrsmittel?.get("mittelText")?.jsonPrimitive?.contentOrNull 
                    ?: verkehrsmittel?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: "Zug"
                val linienNummer = verkehrsmittel?.get("linienNummer")?.jsonPrimitive?.contentOrNull ?: ""
                val terminus = entry["terminus"]?.jsonPrimitive?.contentOrNull ?: ""
                val gleis = entry["gleis"]?.jsonPrimitive?.contentOrNull ?: ""
                
                // Filter by destination direction
                if (!isTrainGoingToDestination(terminus, toStationName, toExtId)) {
                    return@mapNotNull null
                }
                
                // Estimate arrival time
                val estimatedMinutes = estimateTravelTime(extractStationId(fromStationId), toExtId)
                val arrTime = depTime.plusMinutes(estimatedMinutes)
                
                val displayName = if (linienNummer.isNotEmpty()) linienNummer else trainName
                val trainIcon = getTrainIcon(displayName, produktGattung)
                
                Connection(
                    id = "${depTime}_$displayName",
                    trainName = "$displayName*", // * indicates estimated arrival
                    trainIcon = trainIcon,
                    departureTime = depTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    arrivalTime = "~${arrTime.format(DateTimeFormatter.ofPattern("HH:mm"))}", // ~ indicates estimate
                    departureStation = getStationNameFromId(fromStationId),
                    arrivalStation = toStationName,
                    platform = gleis,
                    departureDateTime = depTime
                )
            } catch (e: Exception) {
                Log.e("DBApiClient", "Error parsing departure: ${e.message}")
                null
            }
        }
    }
    
    private fun isTrainGoingToDestination(terminus: String, destinationName: String, destinationExtId: String): Boolean {
        val terminusLower = terminus.lowercase()
        val destLower = destinationName.lowercase()
        
        if (terminusLower.contains(destLower) || destLower.contains(terminusLower)) return true
        
        val knownRoutes = mapOf(
            "8003524" to listOf("lindau", "bregenz", "innsbruck", "münchen", "langenargen"),
            "8000112" to listOf("friedrichshafen", "radolfzell", "singen", "konstanz", "basel", "karlsruhe", "ulm", "stuttgart"),
            "8000230" to listOf("lindau", "bregenz", "innsbruck", "münchen"),
        )
        
        val validTermini = knownRoutes[destinationExtId] ?: emptyList()
        return validTermini.any { terminusLower.contains(it) }
    }
    
    private fun estimateTravelTime(fromExtId: String, toExtId: String): Long {
        val knownRoutes = mapOf(
            "8000112-8003524" to 8L, "8003524-8000112" to 8L,
        )
        return knownRoutes["$fromExtId-$toExtId"] ?: 10L
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
    
    private fun getStationNameFromId(stationId: String): String {
        val match = Regex("O=([^@]+)").find(stationId)
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
