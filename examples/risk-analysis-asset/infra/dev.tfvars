project_id = "risk-analysis-asset"
region = "us-central1"
db_name = "risk-analysis-v11"
db_location_id = "asia-south2"
db_project_id = "risk-analysis-asset"
risk_themes = ["Dependency", "Change Management", "Capacity Planning", "Infrastructure"]
frontend_svc_name = "risk-analysis-frontend-v3"
frontend_svc_location = "us-central1"
frontend_svc_image = "us-central1-docker.pkg.dev/risk-analysis-asset/risk-analysis/risk-analysis-frontend@sha256:1570737cb9c7200caa13cce2ec29e90047e9fe8d8b0e29b3763a797bbcd8487f"
frontend_svc_port = 3000
backend_svc_name = "risk-analysis-backend-v3"
backend_svc_location = "us-central1"
backend_svc_image = "us-central1-docker.pkg.dev/risk-analysis-asset/risk-analysis/risk-analysis-backend@sha256:33bbc8adfe4491006d5beeada72a494c9a8aacf829eff15840f878982819a1af"
backend_svc_port = 5000
env="development"
enabled_apis = [ "run.googleapis.com", "firestore.googleapis.com", "cloudbuild.googleapis.com", "artifactregistry.googleapis.com", "storage.googleapis.com" ]