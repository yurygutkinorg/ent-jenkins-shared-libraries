import groovy.json.JsonSlurper

/*  Make http reqeust call and parse response.
 *
 *  @auther gxu
 */

Map Get(def url){
    HttpURLConnection http = new URL(url).openConnection() as HttpURLConnection
    http.setRequestMethod('GET')
    http.setDoOutput(true)
    http.setRequestProperty("Accept", 'application/json')
    http.setRequestProperty("Content-Type", 'application/json')

    http.outputStream.write(body.getBytes("UTF-8"))
    http.connect()

    def response = [:]    

    if (http.responseCode == 200) {
        response = new JsonSlurper().parseText(http.inputStream.getText('UTF-8'))
    } else {
        response = new JsonSlurper().parseText(http.errorStream.getText('UTF-8'))
    }
    println "response: ${response}"

    return toJson(response)
}

Map Post(def url, def body){
    HttpURLConnection http = new URL(url).openConnection() as HttpURLConnection
    http.setRequestMethod('POST')
    http.setDoOutput(true)
    http.setRequestProperty("Accept", 'application/json')
    http.setRequestProperty("Content-Type", 'application/json')

    http.outputStream.write(body.getBytes("UTF-8"))
    http.connect()

    def response = [:]    

    if (http.responseCode == 200 || http.responseCode == 201) {
        response = new JsonSlurper().parseText(http.inputStream.getText('UTF-8'))
    } else {
        response = new JsonSlurper().parseText(http.errorStream.getText('UTF-8'))
    }
    println "response: ${response}"
    
    return toJson(response)
}

public String getDisplayName(String response){
    JsonSlurper slurper = new JsonSlurper()
    Map res = slurper.parseText(response)
    return res.get('displayName')
}

Map toJson(String response){
    JsonSlurper slurper = new JsonSlurper()
    Map json = slurper.parseText(response)
    return json
}
