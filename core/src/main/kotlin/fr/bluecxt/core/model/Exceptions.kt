// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.model

/**
 * Specific exception for content that is explicitly unavailable on the server (deleted, geoblocked, etc.)
 */
class ContentUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when a server or site is rate-limiting the extension (e.g., 429 Too Many Requests or Cloudflare blocks).
 */
class RateLimitException(message: String) : Exception(message)

/**
 * Generic but descriptive exception for failures during the extraction process (parsing errors, script missing, etc.)
 */
class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Selector exception
 */
class SelectorException(message: String) : Exception(message)
