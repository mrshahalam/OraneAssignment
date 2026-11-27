const tokenKey = "workforce_token";

export function getToken() {
  return localStorage.getItem(tokenKey);
}

export function saveSession(session) {
  localStorage.setItem(tokenKey, session.token);
  localStorage.setItem("workforce_role", session.role);
  localStorage.setItem("workforce_employee_id", String(session.employeeId));
}

export function clearSession() {
  localStorage.removeItem(tokenKey);
  localStorage.removeItem("workforce_role");
  localStorage.removeItem("workforce_employee_id");
}

export function getRole() {
  return localStorage.getItem("workforce_role");
}

export function getEmployeeId() {
  return Number(localStorage.getItem("workforce_employee_id"));
}

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (getToken()) headers.Authorization = `Bearer ${getToken()}`;
  if (!(options.body instanceof FormData) && options.body) {
    headers["Content-Type"] = "application/json";
  }

  const response = await fetch(path, { ...options, headers });
  if (response.status === 204) return null;
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(body?.message || body?.error || "Request failed");
  }
  return body;
}
