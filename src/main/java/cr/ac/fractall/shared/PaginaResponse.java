package cr.ac.fractall.shared;

import java.util.List;
import java.util.UUID;

public record PaginaResponse<T>(List<T> items, UUID nextCursor) {}
