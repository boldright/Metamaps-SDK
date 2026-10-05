#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint metamaps_flutter.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'metamaps_flutter'
  s.version          = '0.6.0'
  s.summary          = 'Embed maps built with Metamaps in Flutter apps, with optional indoor positioning.'
  s.description      = <<-DESC
Embed maps built with Metamaps in Flutter apps, with optional indoor positioning.
                       DESC
  s.homepage         = 'https://metamaps.jp'
  s.license          = { :type => 'Apache-2.0', :file => '../LICENSE' }
  s.author           = { 'Boldright' => 'info@boldright.co.jp' }
  s.source           = { :path => '.' }
  s.source_files = 'metamaps_flutter/Sources/metamaps_flutter/**/*'
  bundled_frameworks = 'metamaps_flutter/Artifacts/MetamapsSDK/Artifacts/*.xcframework'
  s.vendored_frameworks = bundled_frameworks if Dir.glob(File.join(__dir__, bundled_frameworks)).any?
  s.dependency 'Flutter'
  s.platform = :ios, '16.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.9'

  s.resource_bundles = {'metamaps_flutter_privacy' => ['metamaps_flutter/Sources/metamaps_flutter/PrivacyInfo.xcprivacy']}
end
