/**
 * Generic OIDC config for the React Admin Console.
 *
 * When all three env vars are set we wire `react-oidc-context` with PKCE
 * Authorization-Code flow against any spec-compliant OIDC provider
 * (Keycloak, Auth0, Okta, AWS Cognito, Azure AD, Google …).
 *
 * When NOT set, the AuthProvider is skipped entirely so local-dev keeps
 * working with the paste-the-bearer-token UX (no behaviour change).
 */
import { WebStorageStateStore } from "oidc-client-ts";

const authority   = process.env.REACT_APP_OIDC_AUTHORITY;
const clientId    = process.env.REACT_APP_OIDC_CLIENT_ID;
const scope       = process.env.REACT_APP_OIDC_SCOPE || "openid profile email";
const redirectUri = process.env.REACT_APP_OIDC_REDIRECT_URI
  || (typeof window !== "undefined" ? window.location.origin + "/" : undefined);

export const oidcEnabled = Boolean(authority && clientId);

export const oidcConfig = oidcEnabled
  ? {
      authority,
      client_id: clientId,
      redirect_uri: redirectUri,
      post_logout_redirect_uri: redirectUri,
      response_type: "code",
      scope,
      automaticSilentRenew: true,
      loadUserInfo: true,
      // Keep tokens in localStorage so the existing ipaasClient.js
      // (which reads localStorage["valkeyry.bearer-token"]) sees them on cold loads.
      userStore: new WebStorageStateStore({ store: window.localStorage }),
      // Strip the ?code=&state= from the URL after the callback so refreshes don't loop.
      onSigninCallback: () => {
        window.history.replaceState({}, document.title, window.location.pathname);
      },
    }
  : null;
