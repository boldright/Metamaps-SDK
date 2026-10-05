#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint metamap_positioning.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'metamap_positioning'
  s.version          = '0.5.0'
  s.summary          = 'Metamaps BLE indoor positioning and embedded map SDK for Flutter.'
  s.description      = <<-DESC
Metamaps BLE indoor positioning and embedded map SDK for Flutter.
                       DESC
  s.homepage         = 'https://metamaps.jp'
  s.license          = { :type => 'Apache-2.0', :file => '../LICENSE' }
  s.author           = { 'Boldright' => 'info@boldright.co.jp' }
  s.source           = { :path => '.' }
  s.source_files = 'metamap_positioning/Sources/metamap_positioning/**/*'
  bundled_frameworks = 'metamap_positioning/Artifacts/MetamapSDK/Artifacts/*.xcframework'
  s.vendored_frameworks = bundled_frameworks if Dir.glob(File.join(__dir__, bundled_frameworks)).any?
  s.dependency 'Flutter'
  s.platform = :ios, '16.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.9'

  s.resource_bundles = {'metamap_positioning_privacy' => ['metamap_positioning/Sources/metamap_positioning/PrivacyInfo.xcprivacy']}
end
