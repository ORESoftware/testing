use std::{fmt, sync::Arc};

use axum::http::{HeaderMap, header::AUTHORIZATION};
use subtle::ConstantTimeEq;
use thiserror::Error;

use crate::config::Config;

#[derive(Clone)]
pub struct AuthPolicy {
    token: Option<Arc<[u8]>>,
    protect_reads: bool,
}

impl fmt::Debug for AuthPolicy {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("AuthPolicy")
            .field("token_configured", &self.token.is_some())
            .field("protect_reads", &self.protect_reads)
            .finish()
    }
}

#[derive(Clone, Copy, Debug, Error)]
#[error("authentication failed")]
pub struct AuthorizationError;

impl AuthPolicy {
    pub fn from_config(config: &Config) -> Self {
        Self {
            token: config
                .auth_token
                .as_deref()
                .map(|value| Arc::<[u8]>::from(value.as_bytes())),
            protect_reads: config.protect_read_api,
        }
    }

    pub fn authorize_ingest(&self, provided: Option<&str>) -> Result<(), AuthorizationError> {
        self.authorize(provided)
    }

    pub fn authorize_read(&self, provided: Option<&str>) -> Result<(), AuthorizationError> {
        if self.protect_reads {
            self.authorize(provided)
        } else {
            Ok(())
        }
    }

    pub const fn ingestion_is_protected(&self) -> bool {
        self.token.is_some()
    }

    pub const fn reads_are_protected(&self) -> bool {
        self.protect_reads
    }

    fn authorize(&self, provided: Option<&str>) -> Result<(), AuthorizationError> {
        let Some(expected) = self.token.as_deref() else {
            return Ok(());
        };
        let Some(provided) = provided else {
            return Err(AuthorizationError);
        };
        let provided = provided.as_bytes();

        if expected.len() != provided.len() || !bool::from(expected.ct_eq(provided)) {
            return Err(AuthorizationError);
        }
        Ok(())
    }
}

pub fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    let value = headers.get(AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = value.split_once(' ')?;
    if scheme.eq_ignore_ascii_case("bearer") && !token.is_empty() {
        Some(token)
    } else {
        None
    }
}

pub fn preferred_token<'a>(headers: &'a HeaderMap, query_token: Option<&'a str>) -> Option<&'a str> {
    bearer_token(headers).or(query_token)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verifies_tokens() {
        let mut config = Config::local_test();
        config.auth_token = Some("a-strong-enough-token".to_owned());
        let policy = AuthPolicy::from_config(&config);

        assert!(
            policy
                .authorize_ingest(Some("a-strong-enough-token"))
                .is_ok()
        );
        assert!(policy.authorize_ingest(Some("wrong-token-value")).is_err());
        assert!(policy.authorize_ingest(None).is_err());
    }
}
