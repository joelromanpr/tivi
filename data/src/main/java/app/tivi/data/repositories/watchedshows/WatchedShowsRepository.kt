/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.data.repositories.watchedshows

import app.tivi.data.entities.SortOption
import app.tivi.data.entities.Success
import app.tivi.data.instantInPast
import app.tivi.data.repositories.shows.LocalShowStore
import app.tivi.data.repositories.shows.ShowRepository
import app.tivi.extensions.parallelForEach
import org.threeten.bp.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchedShowsRepository @Inject constructor(
    private val localStore: LocalWatchedShowsStore,
    private val localShowStore: LocalShowStore,
    private val lastRequestStore: LocalWatchedShowsLastRequestStore,
    private val traktDataSource: TraktWatchedShowsDataSource,
    private val showRepository: ShowRepository
) {
    fun observeWatchedShowsPagedList(filter: String?, sort: SortOption) = localStore.observePagedList(filter, sort)

    suspend fun needUpdate(expiry: Instant = instantInPast(hours = 12)): Boolean {
        return lastRequestStore.isRequestBefore(expiry)
    }

    suspend fun getWatchedShow(showId: Long) = localStore.getWatchedShow(showId)

    suspend fun getWatchedShows() = localStore.getWatchedShows()

    suspend fun updateWatchedShows() {
        when (val response = traktDataSource.getWatchedShows()) {
            is Success -> {
                response.data.map { (show, entry) ->
                    // Grab the show id if it exists, or save the show and use it's generated ID
                    val watchedShowId = localShowStore.getIdOrSavePlaceholder(show)
                    // Make a copy of the entry with the id
                    entry.copy(showId = watchedShowId)
                }.also { entries ->
                    lastRequestStore.updateLastRequest()
                    // Save the related entries
                    localStore.saveWatchedShows(entries)
                    // Now update all of the related shows if needed
                    entries.parallelForEach { entry ->
                        if (showRepository.needsInitialUpdate(entry.showId)) {
                            showRepository.updateShow(entry.showId)
                        }
                    }
                }
            }
        }
    }
}