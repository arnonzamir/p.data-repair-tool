package com.sunbit.repair.api

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the React SPA index.html for the root path.
 * Spring Boot auto-serves static files from classpath:/static/.
 * Hash-based routing (#/purchase/123) doesn't need server-side forwarding.
 */
@RestController
class SpaController {

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun index(): ResponseEntity<ByteArray> {
        val resource = ClassPathResource("static/index.html")
        return if (resource.exists()) {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(resource.inputStream.readBytes())
        } else {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body("<html><body><h2>Frontend not bundled. Run from dev server at port 3000.</h2></body></html>".toByteArray())
        }
    }
}
