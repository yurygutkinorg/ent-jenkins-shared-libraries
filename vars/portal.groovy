// Temporary hack for helm migration to version 3
String get_helm_bin() {
  return (env.ENVIRONMENT == 'dev') ? 'helm3' : 'helm'
}

String get_portal_version_from_dynamodb(String app_name, String project_name) {
  // Temporary solution. Gonna be changed once we migrate to trunk based dev.
  return sh(
    script: "make python-dynamodb-get-current-version-from-dynamodb PROJECT_NAME=${project_name} APP_NAME=${app_name}",
    returnStdout: true
  ).trim()
}

String helm_get_values(String release_name, String namespace) {
  String helm_values_query = "${get_helm_bin()} get values ${release_name} --output json"

  if (get_helm_bin() == 'helm3') {
    // Helm3 is scoped to namespaces
    helm_values_query += " --namespace ${namespace}"
  }

  return helm_values_query
}

String get_portal_version_from_cluster(String app_name, String project_name) {
  String release_name = "${project_name}-${app_name}-${env.PORTAL_ENV}-ingress"
  String namespace = "${project_name}-${env.PORTAL_ENV}"
  return sh(
    script: "${helm_get_values(release_name, namespace)} | jq -r '.appVersion'",
    returnStdout: true
  ).trim()
}

Boolean is_release_deployed(String app_name, String color, String project_name) {
  String release_name = "${project_name}-${app_name}-${color}-${env.PORTAL_ENV}"
  String namespace = "${project_name}-${env.PORTAL_ENV}"
  return sh(
    script: "${helm_get_values(release_name, namespace)} | jq -r '.deployment.enabled'",
    returnStdout: true
  ).trim().toBoolean()
}

def download_all_configs(project_name) {
  def apps = [
    "web",
    "util",
    "workers",
    "legacy-web",
    "static-worker",
    "dynamic-worker",
  ]

  for (app in apps) {
    sh "APP_NAME=${app} PROJECT_NAME=${project_name} make python-dynamodb-get-values"
  }
  sh "APP_NAME=cron PROJECT_NAME=${project_name} CHART_DIR=helm/cron make python-dynamodb-get-values"
}

String get_live_color(String app_name, String project_name) {
  String release_name = "${project_name}-${app_name}-${env.PORTAL_ENV}-ingress"
  String namespace = "${project_name}-${env.PORTAL_ENV}"
  return sh(
    script: "${helm_get_values(release_name, namespace)} | jq -r '.activeColor' || true",
    returnStdout: true
  ).trim()
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
  if (get_helm_bin() == 'helm') {
    // Helm2 specific
    sh "${get_helm_bin()} init --client-only"
  }

  sh "${get_helm_bin()} package --app-version=${app_version} ${helm_dir}"
}

def get_current_app_version(app_name, project_name) {
  def current_app_version = get_portal_version_from_cluster(app_name, project_name)
  return current_app_version
}

def deploy_new_release(app_name, prod_mode, project_name, domain) {
  echo "PROD MODE passed: ${prod_mode}"

  download_all_configs(project_name)

  def target_color = get_target_color(app_name, project_name)
  def new_app_version = get_portal_version_from_dynamodb(app_name, project_name)
  def helm_dir = "helm/portal"

  package_chart(helm_dir, new_app_version)

  sh """
    ${get_helm_bin()} upgrade \
      --wait \
      --namespace ${project_name}-${env.PORTAL_ENV} \
      --cleanup-on-fail --atomic --install ${project_name}-${app_name}-${target_color}-${env.PORTAL_ENV} \
      --recreate-pods=${prod_mode} \
      --values ${helm_dir}/values.${app_name}.yaml \
      --set domain=${domain} \
      --set nameOverride=${project_name} \
      --set envName=${env.PORTAL_ENV} \
      --set global.appVersion=${new_app_version} \
      --set global.instanceColor=${target_color} \
      --set global.testRelease=${prod_mode} \
      --set deployment.image.repository=${env.DOCKER_REPO} \
      --set deployment.image.tag=${env.DOCKER_IMAGE_TAG} \
      portal-0.0.1.tgz
  """
}

def deploy_portal(app_name, is_prod_mode=false, is_worker=false, project_name, domain) {
  if (env.PORTAL_ENV == "prod") {
    is_prod_mode = true
  }

  def current_app_version = get_current_app_version(app_name, project_name)

  def new_app_version = get_portal_version_from_dynamodb(app_name, project_name)

  def target_color = get_target_color(app_name, project_name)

  deploy_new_release(app_name, is_prod_mode, project_name, domain)

  ingress_enabled = ! is_worker
  if (current_app_version == "null" || current_app_version == "") {
    // setup ingress
    sh """
      ${get_helm_bin()} upgrade \
        --wait \
        --namespace ${project_name}-${env.PORTAL_ENV} \
        --install ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress \
        --set domain=${domain} \
        --set nameOverride=${project_name} \
        --set enabled=${ingress_enabled} \
        --set activeColor=${target_color} \
        --set appVersion=${new_app_version} \
        --set appType=${app_name} \
        --set envName=${env.PORTAL_ENV} \
        helm/main-ingress/
    """
  }

  if (is_prod_mode != true) {
    if (current_app_version != "null" && current_app_version != "") {
      def swapped_color = swap_dns(app_name, ingress_enabled, is_prod_mode, project_name, domain)
      scale_down(app_name, swapped_color, current_app_version, project_name, domain)

      if (is_worker) {
        def exit_status = fix_queues(new_app_version, current_app_version, is_prod_mode)

        if (exit_status != 0) {
          currentBuild.result = 'FAILED'
          error 'queue fix returned non-zero status'
        }
      }
    }
  }
}

def print_pod_logs(app_name, project_name, env){
  sh(script: "sleep 3m ", returnStdout: false)
  live_color = get_live_color(app_name, project_name)
  deploying_pods = sh(script: "kubectl get pods -n ${project_name}-${env} -l 'app=${project_name}, release=${project_name}-${app_name}-${live_color}-${env}' ", returnStdout: true)
  echo "Deploying ${project_name}-${app_name} to pods: \n ${deploying_pods}"
  first_deployed_pod = sh(script: "kubectl get pods -n ${project_name}-${env} -l 'app=${project_name}, release=${project_name}-${app_name}-${live_color}-${env}' -o name | head -n 1 ", returnStdout: true)
  pod_logs = sh(script: "kubectl -n ${project_name}-${env} logs --since=5m ${first_deployed_pod} ", returnStdout: true)
  echo "Logs from ${deploying_pods}: ${pod_logs}"
}

def deploy_cronjobs(project_name) {
  helm_dir = "helm/cron"

  download_all_configs(project_name)

  sh """
    ${get_helm_bin()} upgrade \
      --wait \
      --namespace ${project_name}-${env.PORTAL_ENV} \
      --install portal-cron-${env.PORTAL_ENV} \
      -f ${helm_dir}/values.cron.yaml \
      --set envName=${env.PORTAL_ENV} \
      ${helm_dir}
  """
}

def swap_dns(app_name, ingress_enabled=true, test_release=false, project_name, domain) {
  helm_dir = "helm/main-ingress"

  swapped_color = get_live_color(app_name, project_name)
  target_color = get_target_color(app_name, project_name)
  new_app_version = get_portal_version_from_dynamodb(app_name, project_name)

  sh """
    ${get_helm_bin()} upgrade \
      --wait \
      --namespace ${project_name}-${env.PORTAL_ENV} \
      --install ${project_name}-${app_name}-${env.PORTAL_ENV}-ingress \
      -f helm/custom-values/values.${env.PORTAL_ENV}.yaml \
      --set domain=${domain},nameOverride=${project_name},enabled=${ingress_enabled},activeColor=${target_color},appVersion=${new_app_version},appType=${app_name},envName=${env.PORTAL_ENV} \
      ${helm_dir}
  """
  return swapped_color
}

def scale_down(app_name, color, version, project_name, domain) {
  if (color != "" && color != "null") {
    package_chart("helm/portal", version)

    sh """
      ${get_helm_bin()} upgrade --wait --reuse-values ${project_name}-${app_name}-${color}-${env.PORTAL_ENV}  \
        --set domain=${domain},nameOverride=${project_name},deployment.enabled=false \
        portal-0.0.1.tgz \
        --namespace=${project_name}-${env.PORTAL_ENV}
    """
  }
}

def disable_test_mode_on_release(app_name, color, project_name) {
  return sh(
    script: "${get_helm_bin()} upgrade --wait --install --reuse-values ${project_name}-${app_name}-${color}-${env.PORTAL_ENV} portal-0.0.1.tgz --set global.testRelease=false --namespace ${project_name}-${env.PORTAL_ENV}",
    returnStatus: true
  )
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

Integer fix_queues(new_release, old_release, prod_mode=false) {
  if (env.ENVIRONMENT == 'dev') return 0 // Skip due to broken auth svc

  String environ = env.PORTAL_ENV
  String auth_svc_url = env.AUTH_SVC_URL
  String portal_util_auth_user = env.PORTAL_UTIL_AUTH_USERNAME
  String portal_util_auth_pass = env.PORTAL_UTIL_AUTH_PASSWORD

  String util_url = (environ == "prod") ? "portal-util.guardanthealth.com" : "portal-util-${environ}.guardanthealth.com"
  String auth_token = get_auth_token(portal_util_auth_user, portal_util_auth_pass, auth_svc_url)

  Integer status_code = sh(
    script: """
      curl -f -H "Authorization: ${auth_token}" \
        https://${util_url}/queue_fix \
        -d 'new_release=${environ}-${new_release}' \
        -d 'old_release=${environ}-${old_release}' \
        -d 'prod_mode=${prod_mode}'
    """,
    returnStatus: true
  )
  return status_code
}


String get_auth_token(username, password, url) {
  String data = "{\"username\": \"${username}\", \"password\":\"${password}\"}"
  return sh(
    script: """
      curl -H 'Content-Type: application/json' -X POST --data '${data}' ${url} \
        | jq --raw-output .token
    """,
    returnStdout: true
  ).trim()
}
