import { useEffect, useState } from "react";
import "@/App.css";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider, useAuth } from "react-oidc-context";
import AdminConsole from "@/pages/AdminConsole";
import LoginPage from "@/pages/LoginPage";
import { Toaster } from "@/components/ui/sonner";
import { oidcConfig, oidcEnabled } from "@/auth/oidcConfig";
import { getSession, recordOidcSession, clearSession } from "@/auth/session";

function Gate() {
  const [session, setSession] = useState(getSession());
  // useAuth() is safe to call only when AuthProvider is mounted.
  // eslint-disable-next-line react-hooks/rules-of-hooks
  const auth = oidcEnabled ? useAuth() : null;

  // Sync OIDC user → local session. The OIDC sub-fields are stable references between
  // renders for a given session; we depend on the identity-bearing booleans/objects so the
  // hook re-runs only when the underlying identity actually changes.
  useEffect(() => {
    if (auth?.isAuthenticated && auth.user) {
      const s = recordOidcSession(auth.user);
      if (s) setSession(s);
    }
  }, [auth?.isAuthenticated, auth?.user]);

  const handleSignOut = () => {
    clearSession();
    setSession(null);
    if (auth?.isAuthenticated) auth.signoutRedirect().catch(() => {});
  };

  if (!session) {
    return <LoginPage auth={auth} onSignedIn={setSession} />;
  }
  return <AdminConsole session={session} onSignOut={handleSignOut} />;
}

const Shell = () => (
  <div className="min-h-screen bg-[#0F172A] text-slate-100" data-testid="app-root">
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Gate />} />
        <Route path="*" element={<Gate />} />
      </Routes>
    </BrowserRouter>
    <Toaster theme="dark" />
  </div>
);

function App() {
  if (!oidcEnabled) return <Shell />;
  return (
    <AuthProvider {...oidcConfig}>
      <Shell />
    </AuthProvider>
  );
}

export default App;
