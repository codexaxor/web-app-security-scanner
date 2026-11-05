import { Navigate, Route, Routes } from "react-router";
import Layout from "./components/Layout";
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

/** Declarative routes (react-router v8, BrowserRouter). The side panel is a nested route of the Feed so the
 *  feed stays mounted behind it (/feed/:groupId). */
export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
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
