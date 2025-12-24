package civictech.kernel.germ.port

interface Port<Api> : Serve<Api>, Use<Api>
