export class RequestError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.status = status;
  }
}
export async function request(path, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 9000);
  try {
    const response = await fetch(path, {
      method: body === undefined ? "GET" : "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
      headers: {
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      cache: "no-store",
      signal: controller.signal,
    });
    const json = await response.json();
    if (!response.ok)
      throw new RequestError(
        json.error || "The server could not complete this request.",
        response.status,
      );
    return json;
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(
      error.name === "AbortError"
        ? "The server took too long to respond. Refresh to check the current state, then retry."
        : "Unable to reach TableSignal. Check the local server and try again.",
    );
  } finally {
    clearTimeout(timer);
  }
}
