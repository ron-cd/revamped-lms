package com.librarymanagement.services;

import java.util.List;

/** One page of rows plus the total number of matching rows (for the pager). */
public record PageResult<T>(List<T> items, int total) {}
