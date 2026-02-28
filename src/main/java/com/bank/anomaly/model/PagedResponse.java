package com.bank.anomaly.model;

import java.util.List;

public record PagedResponse<T>(List<T> data, boolean hasMore, String nextCursor) {}
