def get_portal_version_from_dynamodb(app_name) {
  // Temporary solution. Gonna be changed once we migrate to trunk based dev.
  file_path = "portal-${env.PORTAL_ENV}-${app_name}-env.json"
  return sh(returnStdout: true, script: "cat ${file_path} | jq --raw-output '.appVersion'").trim()
}

def get_portal_version_from_cluster(app_name) {
  return sh(script: "helm get values portal-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.appVersion'", returnStdout: true).trim()
}

def bump_version(String app_version) {
  version_parts = app_version.tokenize('.')
  minor = version_parts[2].toInteger() + 1
  return "${version_parts[0]}.${version_parts[1]}.${minor}"
}

def convert_json_to_yaml(src_filename) {
  sh "python deployment-scripts/portal/json-to-yaml.py --file-name ${src_filename}"
}

def copy_yaml_file_to_chart_folder(file_name, target_path) {
  if (!fileExists(file_name)) {
    echo "DynamoDB yaml config file not found. Exiting now."
    sh "exit 1"
    return
  }

  sh "cp ${file_name} ${target_path}"
}

def download_all_configs() {
  apps = [
    "web",
    "util",
    "legacy-web",
    "static-worker",
    "dynamic-worker",
    "cron"
  ]

  withAWS(region:'us-west-2', credentials:'aws_s3_artifacts_credentials') {
    sh "python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key common-config --mode scan"
    sh "python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key common-env --mode scan"
    
    for (app in apps) {
      sh """
        python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key ${app}-config --mode scan
        python merge-jsons.py portal-${env.PORTAL_ENV}-common-config.json portal-${env.PORTAL_ENV}-${app}-config.json --output portal-${env.PORTAL_ENV}-${app}-config.json
        python json-to-yaml.py --file-name portal-${env.PORTAL_ENV}-${app}-config.json

        python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key ${app}-env --mode scan
        python merge-jsons.py portal-${env.PORTAL_ENV}-common-env.json portal-${env.PORTAL_ENV}-${app}-env.json --output portal-${env.PORTAL_ENV}-${app}-env.json
        python json-to-yaml.py --file-name portal-${env.PORTAL_ENV}-${app}-env.json
      """
    }

    sh """
      cp *.yaml helm/portal
      cp portal-${env.PORTAL_ENV}-cron-config.yaml helm/cron
    """
  }
}

def get_live_color(app_name) {
  return sh(script: "helm get values portal-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.activeColor' || true", returnStdout: true).trim()
}

def get_target_color(app_name) {
  live_color = get_live_color(app_name)
  
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

def get_current_app_version(app_name) {
  current_app_version = get_portal_version_from_cluster(app_name)
  return current_app_version
}

def deploy_new_release(app_name, prod_mode) {
  echo "PROD MODE passed: ${prod_mode}"
  download_all_configs()
  target_color = get_target_color(app_name)

  new_app_version = get_portal_version_from_dynamodb(app_name)
  helm_dir = "helm/portal"
  package_chart(helm_dir, new_app_version)

  sh """
    helm upgrade --wait --install portal-${app_name}-${target_color}-${env.PORTAL_ENV} \
      --recreate-pods=${prod_mode} \
      -f ${helm_dir}/values.${app_name}.yaml \
      --set envName=${env.PORTAL_ENV},global.appVersion=${new_app_version},global.instanceColor=${target_color} \
      --set global.testRelease=${prod_mode},deployment.image.repository=${env.DOCKER_REPO},deployment.image.tag=${env.DOCKER_IMAGE_TAG} \
      portal-0.0.1.tgz \
      --namespace portal-${env.PORTAL_ENV}
  """
}

def deploy_portal(app_name, is_prod_mode=false, is_worker=false) {
  if (env.PORTAL_ENV == "prod" || env.PORTAL_ENV == "val") {
    is_prod_mode = true
  }

  current_app_version = get_current_app_version(app_name)
  new_app_version = get_portal_version_from_dynamodb(app_name)
  target_color = get_target_color(app_name)

  deploy_new_release(app_name, is_prod_mode)

  ingress_enabled = ! is_worker
  if (current_app_version == "null" || current_app_version == "") {
    // setup ingress
    sh """
      helm upgrade --wait --install portal-${app_name}-${env.PORTAL_ENV}-ingress \
        --set enabled=${ingress_enabled},activeColor=${target_color},appVersion=${new_app_version},appType=${app_name},envName=${env.PORTAL_ENV} \
        helm/main-ingress/ \
        --namespace portal-${env.PORTAL_ENV} 
    """
  }

  if (is_prod_mode != true) {
    if (current_app_version != "null" && current_app_version != "") {
      swapped_color = swap_dns(app_name, ingress_enabled, is_prod_mode)
      scale_down(app_name, swapped_color, current_app_version)
      
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

def deploy_cronjobs() {
  helm_dir = "helm/cron"

  download_all_configs()

  sh """
    helm upgrade --wait --install portal-cron-${env.PORTAL_ENV} \
      -f ${helm_dir}/values.cron.yaml \
      --set envName=${env.PORTAL_ENV} \
      ${helm_dir} \
      --namespace portal-${env.PORTAL_ENV}
  """
}

def swap_dns(app_name, ingress_enabled=true, test_release=false) {
  helm_dir = "helm/main-ingress"
  
  swapped_color = get_live_color(app_name)
  target_color = get_target_color(app_name)
  new_app_version = get_portal_version_from_dynamodb(app_name)

  sh """
    helm upgrade --wait --install portal-${app_name}-${env.PORTAL_ENV}-ingress \
      --set enabled=${ingress_enabled},activeColor=${target_color},appVersion=${new_app_version},appType=${app_name},envName=${env.PORTAL_ENV} \
      ${helm_dir} \
      --namespace portal-${env.PORTAL_ENV}
  """
  return swapped_color
}

def scale_down(app_name, color, version) {
  if (color != "" && color != "null") {
    package_chart("helm/portal", version)
    sh """
      helm upgrade --wait --reuse-values portal-${app_name}-${color}-${env.PORTAL_ENV} \
        --set deployment.enabled=false \
        portal-0.0.1.tgz \
        --namespace=portal-${env.PORTAL_ENV}
    """
  }
}

def disable_test_mode_on_release(app_name, color) {
  return sh(returnStatus: true, script: "helm upgrade --wait --install --reuse-values portal-${app_name}-${color}-${env.PORTAL_ENV} portal-0.0.1.tgz --set global.testRelease=false --namespace portal-${env.PORTAL_ENV}")
}

def run_prod_mode_post_deployment_operations(app_name) {
  download_all_configs()

  is_worker = false

  current_app_version = get_current_app_version(app_name)
  new_app_version = get_portal_version_from_dynamodb(app_name)
  helm_dir = "helm/portal"
  target_color = get_target_color(app_name)
  package_chart(helm_dir, new_app_version)
  disable_test_mode_on_release(app_name, target_color)

  if (app_name in ["static-worker", "dynamic-worker"]) {
    is_worker = true
  }

  ingress_enabled = !is_worker
  swapped_color = swap_dns(app_name, ingress_enabled, true)
  scale_down(app_name, swapped_color, current_app_version)

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

def merge_jsons(file1, file2, output_path) {
  sh "python deployment-scripts/portal/merge-jsons.py ${file1} ${file2} --output ${output_path}"
}

def switch_context(env_name, kube_config_prefix, k8s_home_dir) {
  KUBE_CONTEXT = 'cluster2.k8s.ghdna.io'

  if (env_name == 'prod') {
    KUBE_CONTEXT = 'prod.k8s.ghdna.io'
  }

  sh """
    # Set kube config to use
    mkdir -p "${k8s_home_dir}"
    cp "${kube_config_prefix}/${KUBE_CONTEXT}/config" "${k8s_home_dir}/config"
    kubectl config use-context ${KUBE_CONTEXT}
  """
}
