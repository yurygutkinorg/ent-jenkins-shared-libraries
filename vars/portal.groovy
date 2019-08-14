def get_portal_version_from_dynamodb(app_name, project_name) {
  // Temporary solution. Gonna be changed once we migrate to trunk based dev.
  return sh(returnStdout: true, script: "make python-dynamodb-get-current-version-from-dynamodb PROJECT_NAME=${project_name} APP_NAME=${app_name}").trim()
}

def get_portal_version_from_cluster(app_name, project_name) {
  return sh(script: "helm get values ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.appVersion'", returnStdout: true).trim()
}

def is_release_deployed(app_name, color, project_name) {
  return sh(script: "helm get values ${project_name}-${app_name}-${color}-${env.PORTAL_ENV} --output json | jq '.deployment.enabled'", returnStdout: true).trim().toBoolean()
}

def download_all_configs(project_name) {
  apps = [
    "web",
    "util",
    "legacy-web",
    "static-worker",
    "dynamic-worker",
  ]

  for (app in apps) {
    sh "APP_NAME=${app} PROJECT_NAME=${project_name} make python-dynamodb-get-values"
  }
  sh "APP_NAME=cron PROJECT_NAME=${project_name} CHART_DIR=helm/cron make python-dynamodb-get-values"
}

def get_live_color(app_name, project_name) {
  echo "helm get values ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.activeColor' || true"
  return sh(script: "helm get values ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.activeColor' || true", returnStdout: true).trim()
}

def get_target_color(app_name, project_name) {
  live_color = get_live_color(app_name, project_name)
  
  // Install/Upgrade a helm release with color tag (e.g. for dev environment, the releases would be dev-web-blue or dev-web-green)
  if ("${live_color}" == "" || "${live_color}" == "null" || "${live_color}" == "blue"){
    return "green"
  }
  else if ("${live_color}" == "green"){
    return "blue"
  }
  else {
    echo "Invalid color-${live_color} found. exiting"
    sh "exit 1"
  }
}

def package_chart(helm_dir, app_version) {
  sh "helm package --app-version=${app_version} --save=false ${helm_dir}"
}

def get_current_app_version(app_name, project_name) {
  current_app_version = get_portal_version_from_cluster(app_name, project_name)
  return current_app_version
}

def deploy_new_release(app_name, prod_mode, project_name, domain) {
  echo "PROD MODE passed: ${prod_mode}"
  download_all_configs(project_name)
  target_color = get_target_color(app_name, project_name)

  new_app_version = get_portal_version_from_dynamodb(app_name, project_name)
  helm_dir = "helm/portal"
  package_chart(helm_dir, new_app_version)

  sh """
    helm upgrade --wait --install ${project_name}-${app_name}-${target_color}-${env.PORTAL_ENV}  \
      --recreate-pods=${prod_mode} \
      -f ${helm_dir}/values.${app_name}.yaml \
      --set domain=${domain},nameOverride=${project_name},envName=${env.PORTAL_ENV},global.appVersion=${new_app_version},global.instanceColor=${target_color} \
      --set global.testRelease=${prod_mode},deployment.image.repository=${env.DOCKER_REPO},deployment.image.tag=${env.DOCKER_IMAGE_TAG} \
      portal-0.0.1.tgz \
      --namespace ${project_name}-${env.PORTAL_ENV}
  """
}

def deploy_portal(app_name, is_prod_mode=false, is_worker=false, project_name, domain) {
  if (env.PORTAL_ENV == "prod" || env.PORTAL_ENV == "val") {
    is_prod_mode = true
  }

  current_app_version = get_current_app_version(app_name, project_name)

  new_app_version = get_portal_version_from_dynamodb(app_name, project_name)

  target_color = get_target_color(app_name, project_name)

  deploy_new_release(app_name, is_prod_mode, project_name, domain)

  ingress_enabled = ! is_worker
  if (current_app_version == "null" || current_app_version == "") {
    // setup ingress
    sh """
      helm upgrade --wait --install ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress \
        --set domain=${domain},nameOverride=${project_name},enabled=${ingress_enabled},activeColor=${target_color},appVersion=${new_app_version},appType=${app_name},envName=${env.PORTAL_ENV} \
        helm/main-ingress/ \
        --namespace ${project_name}-${env.PORTAL_ENV}
    """
  }

  if (is_prod_mode != true) {
    if (current_app_version != "null" && current_app_version != "") {
      swapped_color = swap_dns(app_name, ingress_enabled, is_prod_mode, project_name, domain)
      scale_down(app_name, swapped_color, current_app_version, project_name, domain)
      
      if (is_worker) {
        exit_status = fix_queues(new_app_version, current_app_version, is_prod_mode)
        
        if (exit_status != 0) {
          currentBuild.result = 'FAILED'
          error 'queue fix returned non-zero status'
        }
      }
    }
  }
}

def deploy_cronjobs(project_name) {
  helm_dir = "helm/cron"

  download_all_configs(project_name)

  sh """
    helm upgrade --wait --install portal-cron-${env.PORTAL_ENV} \
      -f ${helm_dir}/values.cron.yaml \
      --set envName=${env.PORTAL_ENV} \
      ${helm_dir} \
      --namespace ${project_name}-${env.PORTAL_ENV}
  """
}

def swap_dns(app_name, ingress_enabled=true, test_release=false, project_name, domain) {
  helm_dir = "helm/main-ingress"
  
  swapped_color = get_live_color(app_name, project_name)
  target_color = get_target_color(app_name, project_name)
  new_app_version = get_portal_version_from_dynamodb(app_name, project_name)

  sh """
    helm upgrade --wait --install ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress \
      --set domain=${domain},nameOverride=${project_name},enabled=${ingress_enabled},activeColor=${target_color},appVersion=${new_app_version},appType=${app_name},envName=${env.PORTAL_ENV} \
      ${helm_dir} \
      --namespace ${project_name}-${env.PORTAL_ENV}
  """
  return swapped_color
}

def scale_down(app_name, color, version, project_name, domain) {
  if (color != "" && color != "null") {
    package_chart("helm/portal", version)
    sh """
      helm upgrade --wait --reuse-values ${project_name}-${app_name}-${color}-${env.PORTAL_ENV}  \
        --set domain=${domain},nameOverride=${project_name},deployment.enabled=false \
        portal-0.0.1.tgz \
        --namespace=${project_name}-${env.PORTAL_ENV}
    """
  }
}

def disable_test_mode_on_release(app_name, color, project_name) {
  return sh(returnStatus: true, script: "helm upgrade --wait --install --reuse-values ${project_name}-${app_name}-${color}-${env.PORTAL_ENV} portal-0.0.1.tgz  --set global.testRelease=false --namespace ${project_name}-${env.PORTAL_ENV}")
}

def run_prod_mode_post_deployment_operations(app_name, project_name, domain) {
  download_all_configs(project_name)

  is_worker = false

  current_app_version = get_current_app_version(app_name, project_name)
  new_app_version = get_portal_version_from_dynamodb(app_name, project_name)
  helm_dir = "helm/portal"
  target_color = get_target_color(app_name, project_name)
  if (!is_release_deployed(app_name, target_color, project_name)) {
    currentBuild.result = 'FAILED'
    error 'No hidden instances deployed'
  }
  package_chart(helm_dir, new_app_version)
  disable_test_mode_on_release(app_name, target_color, project_name)

  if (app_name in ["static-worker", "dynamic-worker"]) {
    is_worker = true
  }

  ingress_enabled = !is_worker
  swapped_color = swap_dns(app_name, ingress_enabled, true, project_name, domain)
  scale_down(app_name, swapped_color, current_app_version, project_name, domain)

  if (is_worker) {
    exit_status = fix_queues(new_app_version, current_app_version, true)

    if (exit_status != 0) {
      currentBuild.result = 'FAILED'
      error 'queue fix returned non-zero status'
    }
  }
}

def fix_queues(new_release, old_release, prod_mode=false) {
  if (env.PORTAL_ENV == "prod") {
    url = "portal-util.guardanthealth.com"
  } else {
    url = "portal-util-${env.PORTAL_ENV}.guardanthealth.com"
  }

  statusCode = sh(
    script: "curl -f -H 'Authorization: ${env.PORTAL_UTIL_API_KEY}' https://${url}/queue_fix -d 'new_release=${env.PORTAL_ENV}-${new_release}' -d 'old_release=${env.PORTAL_ENV}-${old_release}' -d 'prod_mode=${prod_mode}'",
    returnStatus: true  
  )
  return statusCode
}


