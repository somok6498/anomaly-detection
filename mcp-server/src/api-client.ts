/**
 * HTTP client for calling the Spring Boot Anomaly Detection API.
 * Uses native fetch (Node 18+). Base URL is configurable via ANOMALY_API_URL env var.
 */

const BASE_URL = process.env.ANOMALY_API_URL || "http://localhost:8080";

export interface ApiResponse<T = unknown> {
  ok: boolean;
  status: number;
  data: T;
}

export async function apiGet<T = unknown>(
  path: string,
  params?: Record<string, string | number | undefined>
): Promise<ApiResponse<T>> {
  const url = new URL(path, BASE_URL);
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    }
  }
  const res = await fetch(url.toString());
  const data = await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data: data as T };
}

export async function apiPost<T = unknown>(
  path: string,
  body?: unknown,
  params?: Record<string, string | number | undefined>
): Promise<ApiResponse<T>> {
  const url = new URL(path, BASE_URL);
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    }
  }
  const res = await fetch(url.toString(), {
    method: "POST",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data: data as T };
}

export async function apiPut<T = unknown>(
  path: string,
  body?: unknown
): Promise<ApiResponse<T>> {
  const url = new URL(path, BASE_URL);
  const res = await fetch(url.toString(), {
    method: "PUT",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data: data as T };
}

export async function apiDelete(path: string): Promise<ApiResponse> {
  const url = new URL(path, BASE_URL);
  const res = await fetch(url.toString(), { method: "DELETE" });
  const data = await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data };
}

export function formatResult(response: ApiResponse): string {
  if (!response.ok) {
    return JSON.stringify(
      { error: true, status: response.status, details: response.data },
      null,
      2
    );
  }
  return JSON.stringify(response.data, null, 2);
}
