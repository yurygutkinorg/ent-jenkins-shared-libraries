// colorText codes: http://pueblo.sourceforge.net/doc/manual/ansi_color_codes.html
def call(String text, String color = 'green') {
    Map colors = [red: "41", green: "42", yellow: "43", blue: "44", magenta: "45", cyan: "46", white: "47"]
    def selected = colors[color.toLowerCase()]

    if (selected == null) {
        selected = "47"
    }

    wrap([$class: 'AnsiColorBuildWrapper']) {
        echo "\u001B[${selected}m${text}\u001B[0m"
    }
}