package io.earthplatform.geography;

/** Missing data is explicit and must not silently become invented terrain or ocean. */
public final class MissingTerrainException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public MissingTerrainException(String message) { super(message); }
}
