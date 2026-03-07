package com.bank.anomaly.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paginated response wrapper")
public record PagedResponse<T>(
        @Schema(description = "List of items in this page")
        List<T> data,

        @Schema(example = "true", description = "Whether more pages are available")
        boolean hasMore,

        @Schema(example = "eyJ0eCI6MTcwOTg1NjAwMH0=", description = "Cursor for the next page (null if no more pages)")
        String nextCursor
) {}
