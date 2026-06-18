package fr.bluecxt.core

/**
 * Specific exception for content that is explicitly unavailable on the server (deleted, geoblocked, etc.)
 */
class ContentUnavailableException(message: String) : Exception(message)

/**
 * Exception thrown when a server or site is rate-limiting the extension (e.g., 429 Too Many Requests or Cloudflare blocks).
 */
class RateLimitException(message: String) : Exception(message)

/**
 * Generic but descriptive exception for failures during the extraction process (parsing errors, script missing, etc.)
 */
class ExtractionException(message: String) : Exception(message)
