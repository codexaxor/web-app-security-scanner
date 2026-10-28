import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { fetchSession, logout as apiLogout, setUnauthorizedHandler, type SessionInfo } from "./api";
import { queryClient } from "./queryClient";

/**
 * Server-session auth (D-092, amending D-067(a)). On load we ask the server whether a session cookie is still
 * valid (`/auth/session`) — so a refresh keeps the user signed in, unlike the old in-memory Basic credentials.
 * Any 401 afterwards (an expired/idle session, or a sign-out) clears the query cache and drops back to the
 * sign-in gate. The password is never held here; only the server session (cookie) and, in api.ts, the in-memory
 * CSRF token. Nothing is written to web storage.
 */
interface AuthCtx {
  checking: boolean;
  signedIn: boolean;
  username: string | null;
  demoMode: boolean;
  signIn: (info: SessionInfo) => void;
  signOut: () => void;
}

const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [checking, setChecking] = useState(true);
  const [session, setSession] = useState<SessionInfo | null>(null);

  const clear = useCallback(() => {
    setSession(null);
    queryClient.clear();
  }, []);

  // Register the 401 handler used by api.ts for any request (SF-3).
  useEffect(() => {
    setUnauthorizedHandler(clear);
    return () => setUnauthorizedHandler(() => {});
  }, [clear]);

  // Resume an existing session on first load (refresh stays signed in).
  useEffect(() => {
    let active = true;
    fetchSession()
      .then((info) => {
        if (active) {
          setSession(info);
        }
      })
      .catch(() => {
        if (active) {
          setSession(null);
        }
      })
      .finally(() => {
        if (active) {
          setChecking(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  const signOut = useCallback(() => {
    void apiLogout().finally(clear);
  }, [clear]);

  const value: AuthCtx = {
    checking,
    signedIn: session !== null,
    username: session?.username ?? null,
    demoMode: session?.demoMode ?? false,
    signIn: (info: SessionInfo) => setSession(info),
    signOut,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const c = useContext(Ctx);
  if (!c) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return c;
}
