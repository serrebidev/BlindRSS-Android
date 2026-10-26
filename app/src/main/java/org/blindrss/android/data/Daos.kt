package org.blindrss.android.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface FeedDao {
    @Query("SELECT * FROM feeds ORDER BY category, title") fun observeAll(): Flow<List<FeedEntity>>
    @Query("SELECT * FROM feeds ORDER BY category, title") suspend fun all(): List<FeedEntity>
    @Query("SELECT * FROM feeds WHERE lastError IS NOT NULL AND lastError != '' ORDER BY lastErrorAt DESC") fun observeErrors(): Flow<List<FeedEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(f: FeedEntity): Long
    @Update suspend fun update(f: FeedEntity)
    @Delete suspend fun delete(f: FeedEntity)
    @Query("SELECT * FROM feeds WHERE url = :url LIMIT 1") suspend fun byUrl(url: String): FeedEntity?
    @Query("SELECT * FROM feeds WHERE id = :id") suspend fun byId(id: Long): FeedEntity?
    @Query("SELECT * FROM feeds WHERE remoteId = :remoteId LIMIT 1") suspend fun byRemoteId(remoteId: String): FeedEntity?
    @Query("UPDATE feeds SET remoteId = NULL") suspend fun clearRemoteIds()
    // Refresh results touch only status columns, so an edit or mute made while a refresh runs is not overwritten.
    @Query("UPDATE feeds SET lastError = NULL, lastSuccessAt = :at, consecutiveFailures = 0, title = CASE WHEN title = '' THEN :title ELSE title END WHERE id = :id") suspend fun markSuccess(id: Long, at: Long, title: String)
    @Query("UPDATE feeds SET muted = :muted WHERE id = :id") suspend fun setMuted(id: Long, muted: Boolean)
    // What the feed calls itself, refreshed on every parse: siteTitle backs
    // Reset Title, description is shown on the feed's page.
    @Query("UPDATE feeds SET siteTitle = :siteTitle, description = :description WHERE id = :id") suspend fun updateMeta(id: Long, siteTitle: String, description: String)
    @Query("UPDATE feeds SET category = :newName WHERE category = :oldName") suspend fun renameCategory(oldName: String, newName: String)
    @Query("UPDATE feeds SET lastError = :error, lastErrorAt = :at, consecutiveFailures = consecutiveFailures + 1 WHERE id = :id") suspend fun markFailure(id: Long, error: String?, at: Long)
}
// List queries return rows without content and with a 300-character description preview: thousands of full
// articles exhausted the 256 MB heap. Anything that needs the text loads the article by id (byId / observeById).
const val LIST_COLUMNS = "id, feedId, title, url, '' AS content, substr(description, 1, 300) AS description, date, author, isRead, isFavorite, mediaUrl, mediaType, remoteId, chaptersUrl, labels, folder"
@Dao interface ArticleDao {
    @Query("SELECT $LIST_COLUMNS FROM articles ORDER BY date DESC") fun observeAll(): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE isRead = 0 ORDER BY date DESC") fun observeUnread(): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE isRead = 1 ORDER BY date DESC") fun observeRead(): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE isFavorite = 1 ORDER BY date DESC") fun observeFavorites(): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE mediaUrl IS NOT NULL ORDER BY date DESC") fun observePodcasts(): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE feedId = :feedId ORDER BY date DESC") fun observeByFeed(feedId: Long): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%' ORDER BY date DESC") fun search(q: String): Flow<List<ArticleEntity>>
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE title LIKE '%' || :q || '%' ORDER BY date DESC") fun searchTitles(q: String): Flow<List<ArticleEntity>>
    // Age retention: drop non-favorites older than the cutoff.
    @Query("DELETE FROM articles WHERE isFavorite = 0 AND date > 0 AND date < :cutoff") suspend fun deleteOlderThan(cutoff: Long)
    @Query("UPDATE articles SET folder = :folder WHERE id = :id") suspend fun setFolder(id: String, folder: String?)
    @Query("UPDATE articles SET folder = :newName WHERE folder = :oldName") suspend fun renameFolder(oldName: String, newName: String?)
    @Query("SELECT * FROM articles WHERE id = :id") fun observeById(id: String): Flow<ArticleEntity?>
    @Query("SELECT * FROM articles WHERE id = :id") suspend fun byId(id: String): ArticleEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(items: List<ArticleEntity>): List<Long>
    @Update suspend fun update(a: ArticleEntity)
    @Query("UPDATE articles SET isRead = :read WHERE id = :id") suspend fun setRead(id: String, read: Boolean)
    @Query("UPDATE articles SET isFavorite = :fav WHERE id = :id") suspend fun setFavorite(id: String, fav: Boolean)
    @Query("UPDATE articles SET isRead = 1") suspend fun markAllRead()
    @Query("UPDATE articles SET isRead = 1 WHERE feedId = :feedId") suspend fun markFeedRead(feedId: Long)
    @Delete suspend fun delete(a: ArticleEntity)
    @Query("DELETE FROM articles WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM articles WHERE feedId = :feedId") suspend fun deleteByFeed(feedId: Long)
    @Query("DELETE FROM articles WHERE feedId = :feedId AND isFavorite = 0 AND id NOT IN (SELECT id FROM articles WHERE feedId = :feedId ORDER BY date DESC LIMIT :keep)") suspend fun trimFeed(feedId: Long, keep: Int)
    @Query("SELECT * FROM articles WHERE feedId = :feedId AND url = :url AND remoteId IS NULL LIMIT 1") suspend fun localByUrl(feedId: Long, url: String): ArticleEntity?
    @Query("SELECT url FROM articles WHERE feedId = :feedId AND remoteId IS NOT NULL") suspend fun syncedUrls(feedId: Long): List<String>
    @Query("UPDATE articles SET isRead = :read, isFavorite = :fav, remoteId = :remoteId WHERE id = :id") suspend fun setRemoteState(id: String, read: Boolean, fav: Boolean, remoteId: String)
    @Query("SELECT * FROM articles WHERE isRead = 0 AND remoteId IS NOT NULL") suspend fun unreadRemote(): List<ArticleEntity>
    @Query("SELECT * FROM articles WHERE isRead = 0 AND id != :exclude ORDER BY date DESC LIMIT 1") suspend fun nextUnread(exclude: String): ArticleEntity?
    @Query("UPDATE articles SET isRead = 1 WHERE id IN (:ids)") suspend fun markRead(ids: List<String>)
    // Unread, newer than since, not yet tried for offline text (no __fulltext or __fulltext_none label), no media.
    @Query("SELECT * FROM articles WHERE isRead = 0 AND url != '' AND mediaUrl IS NULL AND date > :since AND instr(labels, '__fulltext') = 0 ORDER BY date DESC LIMIT :n") suspend fun offlineCandidates(n: Int, since: Long): List<ArticleEntity>
    @Query("UPDATE articles SET content = :content, labels = :labels WHERE id = :id") suspend fun setContent(id: String, content: String, labels: String)
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 0") fun unreadCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM articles WHERE feedId = :feedId") suspend fun countByFeed(feedId: Long): Int
    @Query("SELECT $LIST_COLUMNS FROM articles WHERE CASE :field WHEN 'content' THEN content WHEN 'author' THEN author ELSE title END LIKE '%' || :value || '%' ORDER BY date DESC") fun smartMatch(field: String, value: String): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE feedId = :feedId") suspend fun byFeed(feedId: Long): List<ArticleEntity>
    @Query("UPDATE articles SET id = :to WHERE id = :from") suspend fun rekey(from: String, to: String)
}
@Dao interface MiscDao {
    // Blank-title rows are permanent-delete id markers, not restorable articles.
    @Query("SELECT * FROM deleted_articles WHERE title != '' ORDER BY deletedAt DESC") fun observeDeleted(): Flow<List<DeletedArticleEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDeleted(d: DeletedArticleEntity)
    @Query("DELETE FROM deleted_articles WHERE id = :id") suspend fun purgeDeleted(id: String)
    @Query("SELECT * FROM deleted_articles WHERE id = :id") suspend fun deletedById(id: String): DeletedArticleEntity?
    @Query("DELETE FROM deleted_articles") suspend fun purgeAllDeleted()
    @Query("SELECT id FROM deleted_articles") suspend fun deletedIds(): List<String>
    @Query("SELECT * FROM deleted_articles WHERE feedId = :feedId") suspend fun deletedByFeed(feedId: Long): List<DeletedArticleEntity>
    @Query("UPDATE deleted_articles SET id = :to WHERE id = :from") suspend fun rekeyDeleted(from: String, to: String)
    @Query("UPDATE downloads SET articleId = :to WHERE articleId = :from") suspend fun rekeyDownload(from: String, to: String)
    @Query("UPDATE playback_state SET articleId = :to WHERE articleId = :from") suspend fun rekeyPlayback(from: String, to: String)
    @Query("UPDATE chapters SET articleId = :to WHERE articleId = :from") suspend fun rekeyChapters(from: String, to: String)
    @Query("SELECT * FROM smart_folders ORDER BY name") fun observeSmart(): Flow<List<SmartFolderEntity>>
    @Insert suspend fun insertSmart(s: SmartFolderEntity)
    @Delete suspend fun deleteSmart(s: SmartFolderEntity)
    @Query("SELECT * FROM filter_rules ORDER BY name") fun observeRules(): Flow<List<FilterRuleEntity>>
    @Query("SELECT * FROM filter_rules WHERE enabled = 1") suspend fun enabledRules(): List<FilterRuleEntity>
    @Insert suspend fun insertRule(r: FilterRuleEntity)
    @Delete suspend fun deleteRule(r: FilterRuleEntity)
    @Query("SELECT * FROM chapters WHERE articleId = :id ORDER BY startMs") suspend fun chapters(id: String): List<ChapterEntity>
    @Query("DELETE FROM chapters WHERE articleId = :id") suspend fun deleteChapters(id: String)
    @Insert suspend fun insertChapters(c: List<ChapterEntity>)
    @Query("SELECT * FROM categories ORDER BY title") fun observeCategories(): Flow<List<CategoryEntity>>
    @Insert suspend fun insertCategory(c: CategoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlayback(p: PlaybackStateEntity)
    @Query("SELECT * FROM playback_state WHERE articleId = :id") suspend fun playback(id: String): PlaybackStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDownload(d: DownloadEntity)
    @Query("SELECT * FROM downloads WHERE articleId = :id") suspend fun download(id: String): DownloadEntity?
    @Query("DELETE FROM downloads WHERE articleId = :id") suspend fun deleteDownload(id: String)
    @Query("SELECT articles.* FROM articles INNER JOIN downloads ON downloads.articleId = articles.id ORDER BY downloads.downloadedAt DESC") fun observeDownloaded(): Flow<List<ArticleEntity>>
    @Query("SELECT articleId FROM downloads") fun observeDownloadIds(): Flow<List<String>>
}
@Database(entities = [FeedEntity::class, ArticleEntity::class, ChapterEntity::class, DeletedArticleEntity::class, CategoryEntity::class, SmartFolderEntity::class, FilterRuleEntity::class, DownloadEntity::class, PlaybackStateEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun miscDao(): MiscDao
}
