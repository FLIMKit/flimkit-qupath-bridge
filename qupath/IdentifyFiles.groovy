import io.github.flimkit.bridge.BridgeClient

import com.google.gson.JsonParser

def baseUrl = args[0]
def token = args[1]
def paths = args[2].split(';')

def client = new BridgeClient(baseUrl, token)

def formats = JsonParser.parseString(client.formats()).getAsJsonObject()
println("FORMATS_OK extensions=${formats.getAsJsonArray('extensions').size()}")

paths.each { path ->
    def reply = JsonParser.parseString(client.identify(path)).getAsJsonObject()
    println("IDENTIFY name=${new File(path).getName()}" +
            " recognised=${reply.get('recognised').getAsBoolean()}" +
            " format=${reply.get('format').getAsString()}" +
            " modality=${reply.get('modality').getAsString()}" +
            " ambiguous=${reply.get('ambiguous').getAsBoolean()}")
}
println('IDENTIFY_DONE')
