def get_portal_version_from_dynamodb() {
  // Temporary solution. Gonna be changed once we migrate to trunk based dev.
  common_file_path = "portal-${env.PORTAL_ENV}-common-env.json"
  return sh(returnStdout: true, script: "cat ${common_file_path} | jq --raw-output '.appVersion'").trim()
}

def get_portal_version_from_cluster(app_name) {
  return sh (script: "helm get values portal-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.appVersion'", returnStdout: true).trim()
}

def bump_version(String app_version) {
  version_parts = app_version.tokenize('.')
  minor = version_parts[2].toInteger() + 1
  return "${version_parts[0]}.${version_parts[1]}.${minor}"
}

def convert_json_to_yaml(src_filename) {
  sh """
    python deployment-scripts/portal/json-to-yaml.py --file-name ${src_filename}
  """
}

def copy_yaml_file_to_chart_folder(file_name, target_path) {
  if (fileExists(file_name)) {
    sh """
      cp ${file_name} ${target_path}
    """
  }
  else {
    echo "dynamodb yaml config file not found. exiting now"
    sh "exit 1"
  }
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
    sh("python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key common-config --mode scan")
    sh("python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key common-env --mode scan")
    for (app in apps) {
      sh("""
        python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key ${app}-config --mode scan
        python merge-jsons.py portal-${env.PORTAL_ENV}-common-config.json portal-${env.PORTAL_ENV}-${app}-config.json --output portal-${env.PORTAL_ENV}-${app}-config.json
        python json-to-yaml.py --file-name portal-${env.PORTAL_ENV}-${app}-config.json

        python dynamodb.py --table-name portal-${env.PORTAL_ENV} --primary-key ${app}-env --mode scan
        python merge-jsons.py portal-${env.PORTAL_ENV}-common-env.json portal-${env.PORTAL_ENV}-${app}-env.json --output portal-${env.PORTAL_ENV}-${app}-env.json
        python json-to-yaml.py --file-name portal-${env.PORTAL_ENV}-${app}-env.json
      """)
    }
    sh(
      """
        cp *.yaml helm/portal
        cp portal-dev-cron-config.yaml helm/cron
      """
    )
  }
}

def get_live_color(app_name) {
  return sh (script: "helm get values portal-${app_name}-${env.PORTAL_ENV}-ingress --output json | jq --raw-output '.activeColor' || true", returnStdout: true).trim()
}

def get_target_color(app_name) {
  live_color = get_live_color(app_name)
  // install/upgrade a helm release with color tag for e.g. for dev environment, the releases would be dev-web-blue or dev-web-green
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

def download_jq() {
  sh ("""
    wget https://github.com/stedolan/jq/releases/download/jq-1.6/jq-linux32
    chmod +x jq-linux32
    mv jq-linux32 /usr/local/bin/jq
  """)
}

def deploy_portal_app(app_name, ingress_enabled=true, prod_mode=false) {
  println "PROD MODE passed: ${prod_mode}"
  download_jq()
  download_all_configs()
  live_color = get_live_color(app_name)
  target_color = get_target_color(app_name)
  app_version = get_portal_version_from_cluster(app_name)
  if (app_version == "null" || app_version == "") {
    app_version = "0.0.0"
  }

  new_app_version = bump_version(app_version)

  helm_dir = "helm/portal"

  if (app_name == "cron") {
    helm_dir = "helm/cron"
  }

  test_release = prod_mode

  if (env.PORTAL_ENV == "prod") {
    test_release = true
  }

  sh("""
    helm upgrade --wait --install portal-${app_name}-${target_color}-${env.PORTAL_ENV} \
    -f ${helm_dir}/values.${app_name}.yaml \
    --set envName=${env.PORTAL_ENV},global.appVersion=${new_app_version},global.instanceColor=${target_color} \
    --set global.testRelease=${test_release},deployment.image.tag=${env.DOCKER_IMAGE_TAG} \
    ${helm_dir} \
    --namespace portal-${env.PORTAL_ENV}
  """)

  if (test_release) {
    return;
  }

  swap_dns(app_name, target_color, new_app_version, ingress_enabled)
  if (live_color != "null" && live_color != "") {
    scale_down(app_name, live_color)
  }
}

def swap_dns(app_name, color, app_version, enabled) {
  sh("helm upgrade --wait --install --set enabled=${enabled},activeColor=${color},appVersion=${app_version},appType=${app_name},envName=${env.PORTAL_ENV} portal-${app_name}-${env.PORTAL_ENV}-ingress helm/main-ingress/ --namespace portal-${env.PORTAL_ENV}")
}

def scale_down(app_name, color) {
  if (app_name == "cron") {
    sh("helm delete --purge portal-${app_name}-${color}-${env.PORTAL_ENV}")
  } else {
    sh("helm upgrade --wait --reuse-values portal-${app_name}-${color}-${env.PORTAL_ENV} helm/portal --set deployment.enabled=false --namespace=portal-${env.PORTAL_ENV}")
  }
}

def disable_test_mode_on_release(color) {
  return sh(returnStatus: true, script: "helm upgrade --wait --install --reuse-values portal-${color}-${env.PORTAL_ENV} portal-helm --set global.testRelease=false --namespace portal-${env.PORTAL_ENV}")
}

def swap_only() {  
  println("parameters passed were environment = ${env.PORTAL_ENV}, docker tag = ${env.DOCKER_IMAGE_TAG}")
  sh """
    wget https://github.com/stedolan/jq/releases/download/jq-1.6/jq-linux32
    chmod +x jq-linux32
    mv jq-linux32 /usr/local/bin/jq
  """
  live_color = get_live_color()
  target_color = get_target_color()
  download_all_configs()
  update_dep()
  disable_test_mode_on_release(target_color)
  app_version = get_portal_version_from_dynamodb()
  swap_dns(target_color, app_version)
  if (live_color != "" && live_color != "null") {
    scale_down(live_color)
  }
}

def merge_jsons(file1, file2, output_path) {
  sh """
    python deployment-scripts/portal/merge-jsons.py ${file1} ${file2} --output ${output_path}
  """
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
