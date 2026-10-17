package com.bookcast.app.library;

import java.util.UUID;

/** Public library snapshot; identity is independent of its title. */
public record Book(UUID id, String title) {}
