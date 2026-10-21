import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { clearCredentials, hasCredentials } from "./api";
import { queryClient, setUnauthorizedHandler } from "./queryClient";

/**
 * In-memory auth (OD-13a). `signedIn` mirrors whether credentials are held in memory (never storage). Any 401
 * — a rejected or expired credential, or a hard refresh that wiped memory — clears the credentials AND the
 * query cache and drops back to the sign-in gate. The URL is left untouched, so after signing in the same route
 * renders (post-refresh route restore, change 10).
 */
interface AuthCtx {
  signedIn: boolean;
  signIn: () => void;
  signOut: () => void;
}

const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState(hasCredentials());

  const dropToSignIn = useCallback(() => {
    clearCredentials();
    queryClient.clear();
    setSignedIn(false);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(dropToSignIn);
    return () => setUnauthorizedHandler(() => {});
  }, [dropToSignIn]);

  const value: AuthCtx = {
    signedIn,
    signIn: () => setSignedIn(true),
    signOut: dropToSignIn,
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
