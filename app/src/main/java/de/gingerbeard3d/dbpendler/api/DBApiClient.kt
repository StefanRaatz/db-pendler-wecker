package de.gingerbeard3d.dbpendler.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Client for Deutsche Bahn official Web API
 * Uses the same API as bahn.de website
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
    
    // Official DB Web API endpoint
    private val baseUrl = "https://int.bahn.de/web/api/reiseloesung"
    
    /**
     * Search for stations by name
     */
    suspend fun searchStations(query: String): Result<List<Station>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/orte?suchbegriff=$encodedQuery&typ=ALL&limit=10"
            
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
                        val type = loc["type"]?.jsonPrimitive?.content ?: ""
                        
                        // Only include stations (ST)
                        if (type != "ST") return@mapNotNull null
                        
                        val name = loc["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val extId = loc["extId"]?.jsonPrimitive?.content ?: ""
                        val id = loc["id"]?.jsonPrimitive?.content ?: extId
                        
                        Station(
                            value = name,
                            id = id,
                            extId = extId,
                            type = type
                        )
                    } catch (e: Exception) {
                        null
                    }
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
            // Extract extId from the full station ID if needed
            val extId = extractExtId(stationId)
            
            val now = LocalDateTime.now()
            val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
            
            val url = "$baseUrl/abfahrten?ortExtId=$extId&datum=$dateStr&zeit=$timeStr"
            
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
                
                val entries = jsonResponse["entries"]?.jsonArray 
                    ?: return@withContext Result.success(emptyList())
                
                val departures = entries.mapNotNull { entryElement ->
                    try {
                        val entry = entryElement.jsonObject
                        
                        // Get time
                        val zeitStr = entry["zeit"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val zeit = LocalDateTime.parse(zeitStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        
                        // Get train info
                        val verkehrsmittel = entry["verkehrmittel"]?.jsonObject
                        val trainName = verkehrsmittel?.get("name")?.jsonPrimitive?.content 
                            ?: verkehrsmittel?.get("mittelText")?.jsonPrimitive?.content
                            ?: "Zug"
                        val linienNummer = verkehrsmittel?.get("linienNummer")?.jsonPrimitive?.content ?: ""
                        val produktGattung = verkehrsmittel?.get("produktGattung")?.jsonPrimitive?.content ?: ""
                        
                        // Get platform
                        val gleis = entry["gleis"]?.jsonPrimitive?.content ?: ""
                        
                        // Get direction (terminus)
                        val terminus = entry["terminus"]?.jsonPrimitive?.content ?: ""
                        
                        // Filter for trains only (not buses) if direction is specified
                        if (direction != null && produktGattung == "BUS") {
                            return@mapNotNull null
                        }
                        
                        // Prefer line number display (RE5, RB31) over just number
                        val displayName = if (linienNummer.isNotEmpty() && !linienNummer.matches(Regex("\\d+"))) {
                            linienNummer
                        } else {
                            trainName
                        }
                        
                        Departure(
                            name = displayName,
                            type = produktGattung,
                            stopid = stationId,
                            stop = "",
                            time = zeit.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                            date = zeit.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                            direction = terminus,
                            track = gleis,
                            product = Product(
                                name = displayName,
                                catOut = produktGattung
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                
                // Filter to only trains (REGIONAL, ICE, EC_IC) if direction specified
                val filteredDepartures = if (direction != null) {
                    departures.filter { it.type in listOf("REGIONAL", "ICE", "EC_IC", "IR", "SBAHN") }
                } else {
                    departures
                }
                
                Result.success(filteredDepartures)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Get connections between two stations
     * Uses departure board + filtering by direction
     */
    suspend fun getConnections(
        fromStationId: String,
        toStationId: String,
        departureTime: LocalDateTime = LocalDateTime.now()
    ): Result<List<Connection>> = withContext(Dispatchers.IO) {
        try {
            val fromExtId = extractExtId(fromStationId)
            val toExtId = extractExtId(toStationId)
            
            val dateStr = departureTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val timeStr = departureTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            
            // Get departures from origin
            val url = "$baseUrl/abfahrten?ortExtId=$fromExtId&datum=$dateStr&zeit=$timeStr"
            
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
                
                val entries = jsonResponse["entries"]?.jsonArray 
                    ?: return@withContext Result.success(emptyList())
                
                // Get station name for filtering and display
                val toStationName = getStationNameFromId(toStationId)
                
                val connections = entries.mapNotNull { entryElement ->
                    try {
                        val entry = entryElement.jsonObject
                        
                        // Only include trains (skip buses)
                        val verkehrsmittel = entry["verkehrmittel"]?.jsonObject
                        val produktGattung = verkehrsmittel?.get("produktGattung")?.jsonPrimitive?.content ?: ""
                        
                        if (produktGattung !in listOf("REGIONAL", "ICE", "EC_IC", "IR", "SBAHN")) {
                            return@mapNotNull null
                        }
                        
                        // Get time
                        val zeitStr = entry["zeit"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val depTime = LocalDateTime.parse(zeitStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        
                        // Skip if departure is in the past
                        if (depTime.isBefore(departureTime)) {
                            return@mapNotNull null
                        }
                        
                        // Get train info
                        val trainName = verkehrsmittel?.get("mittelText")?.jsonPrimitive?.content 
                            ?: verkehrsmittel?.get("name")?.jsonPrimitive?.content
                            ?: "Zug"
                        val linienNummer = verkehrsmittel?.get("linienNummer")?.jsonPrimitive?.content ?: ""
                        
                        val gleis = entry["gleis"]?.jsonPrimitive?.content ?: ""
                        val terminus = entry["terminus"]?.jsonPrimitive?.content ?: ""
                        
                        // Filter: Only include trains that go towards the destination
                        // Check if terminus contains destination name OR is a known terminus for this route
                        if (!isTrainGoingToDestination(terminus, toStationName, toExtId)) {
                            return@mapNotNull null
                        }
                        
                        // Get journeyId for potential detail lookup
                        val journeyId = entry["journeyId"]?.jsonPrimitive?.content ?: ""
                        
                        // Calculate estimated arrival (rough estimate: add typical travel time)
                        // For short regional trips, assume 5-15 minutes
                        val estimatedMinutes = estimateTravelTime(fromExtId, toExtId)
                        val arrTime = depTime.plusMinutes(estimatedMinutes)
                        
                        val displayName = if (linienNummer.isNotEmpty()) linienNummer else trainName
                        val trainIcon = getTrainIcon(displayName, produktGattung)
                        
                        Connection(
                            id = journeyId.ifEmpty { "${depTime}_$displayName" },
                            trainName = displayName,
                            trainIcon = trainIcon,
                            departureTime = depTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            arrivalTime = arrTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                            departureStation = getStationNameFromId(fromStationId),
                            arrivalStation = toStationName,
                            platform = gleis,
                            departureDateTime = depTime
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                
                // Return first 5 connections
                Result.success(connections.take(5))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Extract extId from full station ID
     * Input: "A=1@O=Friedrichshafen Stadt@X=9473902@Y=47653220@U=80@L=8000112@..."
     * Output: "8000112"
     */
    private fun extractExtId(stationId: String): String {
        // If it's already just a number, return it
        if (stationId.matches(Regex("\\d+"))) {
            return stationId
        }
        
        // Extract L= parameter (the EVA number)
        val match = Regex("L=(\\d+)").find(stationId)
        return match?.groupValues?.get(1) ?: stationId
    }
    
    /**
     * Get station name from full station ID
     */
    private fun getStationNameFromId(stationId: String): String {
        // Extract O= parameter (station name)
        val match = Regex("O=([^@]+)").find(stationId)
        return match?.groupValues?.get(1) ?: stationId
    }
    
    /**
     * Check if a train goes towards the destination
     * Based on terminus (final destination) matching or known route patterns
     */
    private fun isTrainGoingToDestination(terminus: String, destinationName: String, destinationExtId: String): Boolean {
        val terminusLower = terminus.lowercase()
        val destLower = destinationName.lowercase()
        
        // Direct match: terminus contains destination name
        if (terminusLower.contains(destLower) || destLower.contains(terminusLower)) {
            return true
        }
        
        // Known route patterns for Bodensee area
        // Trains going to these termini will stop at intermediate stations
        val knownRoutes = mapOf(
            // Destination: Langenargen (8003524) - trains going east towards Lindau
            "8003524" to listOf("lindau", "bregenz", "innsbruck", "münchen", "langenargen"),
            // Destination: Friedrichshafen (8000112) - trains going west
            "8000112" to listOf("friedrichshafen", "radolfzell", "singen", "konstanz", "basel", "karlsruhe", "ulm", "stuttgart"),
            // Destination: Lindau (8000230)
            "8000230" to listOf("lindau", "bregenz", "innsbruck", "münchen"),
        )
        
        val validTermini = knownRoutes[destinationExtId] ?: emptyList()
        return validTermini.any { terminusLower.contains(it) }
    }
    
    /**
     * Estimate travel time between two stations (rough estimate)
     */
    private fun estimateTravelTime(fromExtId: String, toExtId: String): Long {
        // Common routes in the Bodensee area
        val knownRoutes = mapOf(
            // Friedrichshafen Stadt <-> Langenargen
            "8000112-8003524" to 8L,
            "8003524-8000112" to 8L,
            // Add more known routes as needed
        )
        
        val key = "$fromExtId-$toExtId"
        return knownRoutes[key] ?: 10L // Default 10 minutes for unknown routes
    }
    
    private fun getTrainIcon(name: String, produktGattung: String): String {
        return when {
            name.startsWith("ICE") || produktGattung == "ICE" -> "🚄"
            name.startsWith("IC") || name.startsWith("EC") || produktGattung == "EC_IC" -> "🚃"
            name.startsWith("RE") || name.startsWith("RB") || produktGattung == "REGIONAL" -> "🚆"
            name.startsWith("S") || produktGattung == "SBAHN" -> "🚈"
            name.startsWith("U") -> "🚇"
            produktGattung == "BUS" -> "🚌"
            else -> "🚂"
        }
    }
}
