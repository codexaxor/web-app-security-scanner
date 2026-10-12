import { useEffect, useState } from "react";
import { api, hasCredentials } from "./api";
import SignInGate from "./components/SignInGate";
import ConnectGitLab from "./components/ConnectGitLab";
import SelectRepositories from "./components/SelectRepositories";

type Tab = "connect" | "repositories";

export default function App() {
  const [signedIn, setSignedIn] = useState(hasCredentials());
  const [tab, setTab] = useState<Tab>("connect");
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!signedIn) return;
    api
      .listConnections(0, 1)
      .then((c) => {
        if (c.totalElements > 0) {
          setConnected(true);
          setTab("repositories");
        }
      })
      .catch(() => setSignedIn(false)); // 401 → need to sign in again
  }, [signedIn]);

  return (
    <main style={{ fontFamily: "system-ui, sans-serif", padding: "2rem", maxWidth: 820, margin: "0 auto" }}>
      <h1>SecScan</h1>
      {!signedIn ? (
        <SignInGate onSignedIn={() => setSignedIn(true)} />
      ) : (
        <>
          <nav style={{ display: "flex", gap: "1rem", margin: "1rem 0" }}>
            <button onClick={() => setTab("connect")} disabled={tab === "connect"}>
              Connect GitLab
            </button>
            <button onClick={() => setTab("repositories")} disabled={tab === "repositories" || !connected}>
              Repositories
            </button>
          </nav>
          {tab === "connect" ? (
            <ConnectGitLab onChanged={(any) => setConnected(any)} />
          ) : (
            <SelectRepositories />
          )}
        </>
      )}
    </main>
  );
}
