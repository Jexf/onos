# Gui2FwLibApp

This project separates out the Framework part of the ONOS GUI2 project in to a separate Angular library

It is separate to the main ONOS GUI2 project which is in ~/onos/web/gui2

The reason this has been separated out in to a separate library is to allow
external applications e.g. YangGUI to use it, without bringing along the
whole of GUI 2

This project was generated with [Angular CLI](https://github.com/angular/angular-cli) version 6.0.0.

A couple of good articles on the creation and use of __libraries__ in Angular 6 is given in

[The Angular Library Series - Creating a Library with the Angular CLI](https://blog.angularindepth.com/creating-a-library-in-angular-6-87799552e7e5)

and

[The Angular Library Series - Building and Packaging](https://blog.angularindepth.com/creating-a-library-in-angular-6-part-2-6e2bc1e14121)

The Bazel build of this library handles the building and packaging of the library
so that other projects and libraries can use it.

## Development server

To build the library project using Angular CLI run 'ng build --prod gui2-fw-lib'
inside the ~/onos/web/gui2-fw-lib folder

To make the library in to an NPM package use 'npm pack' inside the dist/gui2-fw-lib folder

To build the app that surrounds the library run 'ng build'. This app is not
part of the ONOS GUI and is there as a placeholder for testing the library

Run `ng serve` for a dev server. Navigate to `http://localhost:4200/`.
The app will automatically reload if you change any of the source files.
__NOTE__ If you make changes to files in the library, the app will not pick them up until you build the library again

## Code scaffolding

Run `ng generate component component-name --project=gui2-fw-lib` to generate a new component. You can also use `ng generate directive|pipe|service|class|guard|interface|enum|module`.

## Build

Run `ng build` to build the project. The build artifacts will be stored in the `dist/` directory. Use the `--prod` flag for a production build.

## Running unit tests

Run `ng test` to execute the unit tests via [Karma](https://karma-runner.github.io).

## Running end-to-end tests

Run `ng e2e` to execute the end-to-end tests via [Protractor](http://www.protractortest.org/).

## Further help

To get more help on the Angular CLI use `ng help` or go check out the [Angular CLI README](https://github.com/angular/angular-cli/blob/master/README.md).
