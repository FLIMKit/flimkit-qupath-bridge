import io.github.flimkit.bridge.Discovery

import java.nio.file.Paths

def path = Paths.get(args[0])
def details = Discovery.read(path)
println("DISCOVERY_OK url=${details.url()} token=${details.token()} pid=${details.pid()} stale=${details.stale()}")
