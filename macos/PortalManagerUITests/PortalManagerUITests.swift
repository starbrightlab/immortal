/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import XCTest

final class PortalManagerUITests: XCTestCase {
    func testApplicationLaunches() {
        let application = XCUIApplication()
        application.launch()
        XCTAssertTrue(application.windows.firstMatch.waitForExistence(timeout: 5))
    }
}
