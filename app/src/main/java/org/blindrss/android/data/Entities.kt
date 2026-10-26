package org.blindrss.android.data
import androidx.room.*

@Entity(tableName = "feeds")
data class FeedEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String, val category: String = "Uncategorized", val iconUrl: String? = null, val lastError: String? = null, val lastErrorAt: Long? = null, val lastSuccessAt: Long? = null, val consecutiveFailures: Int = 0, val muted: Boolean = false, val remoteId: String? = null, val description: String = "", val siteTitle: String = "")

@Entity(tableName = "articles", indices = [Index("feedId"), Index("isRead"), Index("isFavorite")])
data class ArticleEntity(@PrimaryKey val id: String, val feedId: Long, val title: String, val url: String, val content: String = "", val description: String = "", val date: Long = 0, val author: String = "", val isRead: Boolean = false, val isFavorite: Boolean = false, val mediaUrl: String? = null, val mediaType: String? = null, val remoteId: String? = null, val chaptersUrl: String? = null, val labels: String = "", val folder: String? = null)

@Entity(tableName = "chapters")
data class ChapterEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val articleId: String, val startMs: Long, val title: String)

@Entity(tableName = "deleted_articles")
data class DeletedArticleEntity(@PrimaryKey val id: String, val feedId: Long, val title: String, val url: String, val content: String = "", val description: String = "", val date: Long = 0, val author: String = "", val isRead: Boolean = false, val isFavorite: Boolean = false, val mediaUrl: String? = null, val mediaType: String? = null, val deletedAt: Long = System.currentTimeMillis())

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val parentId: Long? = null)

@Entity(tableName = "smart_folders")
data class SmartFolderEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val query: String = "", val field: String = "title", val op: String = "contains", val value: String = "")

@Entity(tableName = "filter_rules")
// action holds one action or a comma-separated ordered list (the desktop
// pipeline); stop ends rule processing for the article after this rule.
data class FilterRuleEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val field: String = "title", val op: String = "contains", val value: String = "", val action: String = "mark_read", val target: String? = null, val enabled: Boolean = true, val stop: Boolean = false)

@Entity(tableName = "downloads")
data class DownloadEntity(@PrimaryKey val articleId: String, val filePath: String, val downloadedAt: Long = System.currentTimeMillis())

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(@PrimaryKey val articleId: String, val positionMs: Long = 0, val durationMs: Long = 0, val completed: Boolean = false)
