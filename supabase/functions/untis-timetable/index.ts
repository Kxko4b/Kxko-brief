// untis-timetable: returns the signed-in user's Untis timetable and homework.
// The WebUntis secrets stay inside the existing `untis-test` function.
// This function only checks who is asking, then forwards the request on the server.
//
// Access rule: only the login haleannson@gmail.com may read the timetable.
// - the user must be signed in (valid Supabase JWT)
// - and the email must be in ALLOWED_EMAILS

import { createClient } from "npm:@supabase/supabase-js@2";

const ALLOWED_ROLES: string[] = [];
const ALLOWED_EMAILS: string[] = ["haleannson@gmail.com"]; // only this login

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "GET") return json({ success: false, error: "Use GET." }, 405);

  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return json({ success: false, error: "Sign in first." }, 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const admin = createClient(supabaseUrl, serviceKey);

  // 1) Who is asking?
  const { data: userData, error: userError } = await admin.auth.getUser(token);
  if (userError || !userData?.user) return json({ success: false, error: "Invalid session." }, 401);
  const user = userData.user;

  // 2) Are they allowed to see the timetable?
  const role = (user.app_metadata?.role as string | undefined) ?? "";
  const email = (user.email ?? "").toLowerCase();
  const allowed =
    ALLOWED_ROLES.includes(role) || ALLOWED_EMAILS.map((e) => e.toLowerCase()).includes(email);
  if (!allowed) return json({ success: false, error: "No access to the timetable." }, 403);

  // 3) Forward to the server-only Untis function (it checks the service key itself).
  const url = new URL(req.url);
  const date = url.searchParams.get("date");
  const target = `${supabaseUrl}/functions/v1/untis-test${date ? `?date=${encodeURIComponent(date)}` : ""}`;
  const upstream = await fetch(target, {
    headers: { authorization: `Bearer ${serviceKey}` },
  });
  const body = await upstream.text();
  return new Response(body, {
    status: upstream.status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
});
