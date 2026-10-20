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
}
@Dao interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY date DESC") fun observeAll(): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE isRead = 0 ORDER BY date DESC") fun observeUnread(): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE isRead = 1 ORDER BY date DESC") fun observeRead(): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE isFavorite = 1 ORDER BY date DESC") fun observeFavorites(): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE feedId = :feedId ORDER BY date DESC") fun observeByFeed(feedId: Long): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM articles WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%' ORDER BY date DESC") fun search(q: String): Flow<List<ArticleEntity>>
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
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 0") fun unreadCount(): Flow<Int>
}
@Dao interface MiscDao {
    @Query("SELECT * FROM deleted_articles ORDER BY deletedAt DESC") fun observeDeleted(): Flow<List<DeletedArticleEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDeleted(d: DeletedArticleEntity)
    @Query("DELETE FROM deleted_articles WHERE id = :id") suspend fun purgeDeleted(id: String)
    @Query("SELECT * FROM deleted_articles WHERE id = :id") suspend fun deletedById(id: String): DeletedArticleEntity?
    @Query("DELETE FROM deleted_articles") suspend fun purgeAllDeleted()
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
}
@Database(entities = [FeedEntity::class, ArticleEntity::class, ChapterEntity::class, DeletedArticleEntity::class, CategoryEntity::class, SmartFolderEntity::class, FilterRuleEntity::class, DownloadEntity::class, PlaybackStateEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun miscDao(): MiscDao
}
