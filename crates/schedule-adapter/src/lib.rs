use serde::{Deserialize, Serialize};
use thiserror::Error;
use url::Url;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdapterManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub license: String,
    pub entry: String,
    pub source_url: Option<String>,
    pub allowed_origins: Vec<String>,
    pub capabilities: Vec<AdapterCapability>,
    pub sha256: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AdapterCapability {
    ReadCurrentPage,
    NetworkFetch,
    SaveCourses,
    SaveTimeScheme,
    SaveTermConfig,
    UiPrompt,
}

#[derive(Debug, Error)]
pub enum AdapterPolicyError {
    #[error("adapter does not allow origin: {0}")]
    OriginDenied(String),
    #[error("invalid URL: {0}")]
    InvalidUrl(String),
}

impl AdapterManifest {
    pub fn allows_url(&self, candidate: &str) -> Result<(), AdapterPolicyError> {
        let url = Url::parse(candidate).map_err(|_| AdapterPolicyError::InvalidUrl(candidate.to_string()))?;
        let origin = url.origin().ascii_serialization();
        if self.allowed_origins.iter().any(|allowed| allowed == &origin) {
            Ok(())
        } else {
            Err(AdapterPolicyError::OriginDenied(origin))
        }
    }

    pub fn supports(&self, capability: AdapterCapability) -> bool {
        self.capabilities.contains(&capability)
    }
}

pub mod shiguang {
    use super::*;

    pub fn compatibility_manifest(adapter_id: &str, name: &str, import_url: &str) -> Result<AdapterManifest, AdapterPolicyError> {
        let url = Url::parse(import_url).map_err(|_| AdapterPolicyError::InvalidUrl(import_url.to_string()))?;
        Ok(AdapterManifest {
            id: format!("shiguang.{adapter_id}"),
            name: name.to_string(),
            version: "compat-v1".to_string(),
            license: "external".to_string(),
            entry: String::new(),
            source_url: None,
            allowed_origins: vec![url.origin().ascii_serialization()],
            capabilities: vec![
                AdapterCapability::ReadCurrentPage,
                AdapterCapability::NetworkFetch,
                AdapterCapability::SaveCourses,
                AdapterCapability::SaveTimeScheme,
                AdapterCapability::SaveTermConfig,
                AdapterCapability::UiPrompt,
            ],
            sha256: None,
        })
    }
}
