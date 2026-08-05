// This file is for the proprietary icy deployment system.
{
  name: 'abla-mobile-fullstack',
  machine: 'oxente',
  targets: {
    production: {
      hooks: {
        build: 'make server',
      },
      services: {
        api: {
          type: 'abla-prebuilt',
          host: 'abla-svc.oxente.pt',
          object_path: 'build/server-prebuilt.o',
          assets_path: 'assets',
          port: 8080,
        },
      },
    },
  },
}
