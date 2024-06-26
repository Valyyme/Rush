package com.shub39.rush.genius

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.shub39.rush.database.SearchResult
import com.shub39.rush.database.Song
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object SongProvider {
    private const val TAG = "GeniusProvider"
    private const val BASE_URL = "https://api.genius.com/"
    private const val AUTH_HEADER = "Authorization"
    private const val BEARER_TOKEN = "Bearer ${Tokens.GENIUS_API}"

    private val apiService: ApiService

    init {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val newRequest: Request = chain.request().newBuilder()
                .addHeader(AUTH_HEADER, BEARER_TOKEN)
                .build()
            chain.proceed(newRequest)
        }.build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    fun search(query: String): Result<List<SearchResult>> {
        return try {
            val response: Response<JsonElement> = apiService.search(query).execute()
            if (response.isSuccessful) {
                val jsonHits = response.body()?.asJsonObject
                    ?.getAsJsonObject("response")
                    ?.getAsJsonArray("hits")
                    ?: return Result.failure(Exception("Failed to parse search results"))

                val results = jsonHits.mapNotNull {
                    try {
                        val jo = it.asJsonObject.getAsJsonObject("result")

                        val title = jo.get("title").asString
                        val artist = jo.getAsJsonObject("primary_artist").get("name").asString
                        val album = jo.getAsJsonObject("album")?.get("name")?.asString
                        val artUrl = jo.get("header_image_thumbnail_url").asString
                        val url = jo.get("url").asString
                        val id = jo.get("id").asLong

                        SearchResult(title, artist, album, artUrl, url, id)
                    } catch (e: Exception) {
                        Log.e(TAG, e.message, e)
                        null
                    }
                }

                Result.success(results)
            } else {
                Result.failure(Exception("Search request failed"))
            }
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
            Result.failure(e)
        }
    }

    fun fetchLyrics(songId: Long): Result<Song> {
        Log.i(TAG, "Fetching song $songId")
        return try {
            val response: Response<JsonElement> = apiService.getSong(songId).execute()
            if (response.isSuccessful) {
                val jsonSong = response.body()?.asJsonObject
                    ?.getAsJsonObject("response")
                    ?.getAsJsonObject("song")
                    ?: return Result.failure(Exception("Failed to parse song info"))

                val title = jsonSong.get("title")?.asString ?: "Unknown Title"
                val artist = jsonSong.getAsJsonObject("primary_artist")?.get("name")?.asString ?: "Unknown Artist"
                val sourceUrl = jsonSong.get("url")?.asString ?: ""
                val album = getAlbum(jsonSong)
                val artUrl = jsonSong.get("header_image_thumbnail_url")?.asString ?: ""

                val lyricsJsonElement = jsonSong.getAsJsonObject("lyrics")?.getAsJsonObject("dom")
                val lyrics = if (lyricsJsonElement != null) {
                    parseLyricsJsonTag(lyricsJsonElement)
                } else {
                    "Lyrics not available"
                }

                Result.success(
                    Song(
                        songId,
                        title,
                        artist,
                        lyrics,
                        album,
                        sourceUrl,
                        artUrl
                    )
                )
            } else {
                Result.failure(Exception("Lyrics request failed"))
            }
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
            Result.failure(e)
        }
    }

    private fun getAlbum(jsonObject: JsonObject): String? {
        val albumJson = jsonObject["album"] ?: return null
        if (albumJson.isJsonNull) return null
        return albumJson.asJsonObject.get("name").asString
    }

    private fun parseLyricsJsonTag(lyricsJsonTag: JsonElement): String {
        if (lyricsJsonTag.isJsonPrimitive) return lyricsJsonTag.asString

        val jsonObject = lyricsJsonTag.asJsonObject
        if (jsonObject.has("tag") && jsonObject.get("tag").asString == "br") {
            return "\n"
        }

        if (jsonObject.has("children")) {
            var text = ""
            val jsonChildren = jsonObject.getAsJsonArray("children")
            for (jsonChild in jsonChildren) {
                text += parseLyricsJsonTag(jsonChild)
            }
            return text
        }

        return ""
    }

}