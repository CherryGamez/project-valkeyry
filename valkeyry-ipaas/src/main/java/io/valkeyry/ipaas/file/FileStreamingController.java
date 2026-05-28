package io.valkeyry.ipaas.file;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@Tag(name = "File Streaming",
     description = "Claim-Check inbound streaming uploads & outbound chunked delivery.")
@RestController
@Validated
@RequestMapping("/api/v1/{tenantId}/{projectId}/files")
@RequiredArgsConstructor
public class FileStreamingController {

    private final ReactiveFileStreamingService service;

    @Operation(summary = "Stream a multipart file into the dynamic storage backend; emit Claim Ticket onto the target queue.")
    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    public Mono<ClaimTicket> upload(@PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String tenantId,
                                    @PathVariable @Pattern(regexp = io.valkeyry.ipaas.security.WorkspaceId.PATTERN, message = io.valkeyry.ipaas.security.WorkspaceId.MESSAGE) String projectId,
                                    @RequestPart("storageName")        String storageName,
                                    @RequestPart("targetDestination")  String targetDestination,
                                    @RequestPart("objectKey")          String objectKey,
                                    @RequestPart("file")               FilePart file) {
        Flux<DataBuffer> body = file.content();
        String contentType = file.headers().getContentType() == null
                ? "application/octet-stream" : file.headers().getContentType().toString();
        long length = file.headers().getContentLength();
        return service.ingestAndPublish(tenantId, projectId, storageName, targetDestination,
                objectKey, contentType, length, body);
    }
}
