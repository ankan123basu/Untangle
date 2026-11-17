# ADR-007: Plain DTOs on success, RFC 9457 Problem Details on error

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0 (implemented in Phases 2–3)

## Context

The brief's folder layout mentions an `ApiResponse` wrapper, while its API section mandates RFC 9457
Problem Details for errors. Using both would give clients two different envelope styles to handle.

## Options considered

1. **Envelope everything** (`{success, data, error}`). Uniform shape, but it duplicates the HTTP
   status code, fights with Problem Details, and makes the generated OpenAPI types noisier.
2. **Plain DTOs on success, Problem Details on error.** HTTP status carries success/failure; bodies
   are exactly the resource. Paginated lists use a `PageResponse<T>` type.

## Decision

Option 2. Success: `200/201` with the DTO (or `PageResponse<T>` =
`{content, page, size, totalElements, totalPages}`). Error: `application/problem+json` with
`type`, `title`, `status`, `detail`, `instance`, plus `code` (from `ErrorCode`), `traceId` and
`errors[]` for field errors.

## Consequences

- **Pros:** standard and self-describing; clean `openapi-typescript` types; one place
  (`GlobalExceptionHandler`) shapes every error; the frontend maps `code` to friendly text in one place.
- **Cons:** clients must check the HTTP status (Axios already does).

## Simple explanation

When a request works, you get back exactly what you asked for. When it fails, you get an error in a
standard format with a short code that tells the app what went wrong.
