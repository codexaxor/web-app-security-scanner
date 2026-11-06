import { Navigate, Route, Routes } from "react-router";
import Layout from "./components/Layout";
import HomePage from "./pages/HomePage";
import LoginPage from "./pages/LoginPage";
import SignUpPage from "./pages/SignUpPage";
import DashboardPage from "./pages/DashboardPage";
import FeedPage from "./pages/FeedPage";
import RepositoriesPage from "./pages/RepositoriesPage";
import RepositoryPage from "./pages/RepositoryPage";
import IgnoredPage from "./pages/IgnoredPage";
import SettingsPage from "./pages/SettingsPage";
import ConnectPage from "./pages/ConnectPage";
import SelectPage from "./pages/SelectPage";
import OperatorSignUpsPage from "./pages/admin/OperatorSignUpsPage";
import OperatorUsersPage from "./pages/admin/OperatorUsersPage";

/**
 * Declarative routes (react-router v8, BrowserRouter). M8.13 splits the app into public and protected areas:
 *   - "/" is the public marketing homepage, "/login" the sign-in page, "/signup" self sign-up — all reachable
 *     signed out, outside the app shell.
 *   - Everything under {@link Layout} is the app shell; existing app routes keep their paths (no /app prefix).
 *     Layout redirects a signed-out visitor to /login?next=<path>, so a deep link survives sign-in.
 * The side panel is a nested route of the Feed so the feed stays mounted behind it (/feed/:groupId).
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignUpPage />} />
      <Route element={<Layout />}>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/feed" element={<FeedPage />} />
        <Route path="/feed/:groupId" element={<FeedPage />} />
        <Route path="/repositories" element={<RepositoriesPage />} />
        <Route path="/repositories/:id" element={<RepositoryPage />} />
        <Route path="/ignored" element={<IgnoredPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/connect" element={<ConnectPage />} />
        <Route path="/select" element={<SelectPage />} />
        {/* M8.10 operator pages: routed for everyone; the API answers 403 to non-operators (D-094). */}
        <Route path="/admin/sign-ups" element={<OperatorSignUpsPage />} />
        <Route path="/admin/users" element={<OperatorUsersPage />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Route>
    </Routes>
  );
}
