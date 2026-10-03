#import <Foundation/Foundation.h>

@interface Greeter : NSObject
- (void)greet:(NSString *)name;
@end

@implementation Greeter
- (void)greet:(NSString *)name
{
    // say hello
    NSLog(@"Hello, %@ %d", name, 0X2A);
}
@end
